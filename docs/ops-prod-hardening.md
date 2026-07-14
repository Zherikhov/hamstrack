# Prod hardening runbook

Remaining PLAN.md Phase 7 backlog items that require AWS console / Cloudflare
dashboard access (no AWS credentials exist on the dev machine or the EC2
instance — these steps must be run by an account admin, e.g. in
[CloudShell](https://console.aws.amazon.com/cloudshell)). Server-side steps can
be done over SSH afterwards.

Facts used below: region `eu-north-1`, instance IP `51.20.104.29`
(`ip-172-31-26-22`), app dir `/opt/hamstrack`, compose project `hamstrack`,
only Caddy (80/443) is exposed publicly.

---

## 1. Attachments → S3

### AWS side (CloudShell)

```bash
REGION=eu-north-1
BUCKET=hamstrack-attachments-prod
INSTANCE_ID=$(aws ec2 describe-instances --region $REGION \
  --filters "Name=ip-address,Values=51.20.104.29" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)

# Private bucket
aws s3api create-bucket --bucket $BUCKET --region $REGION \
  --create-bucket-configuration LocationConstraint=$REGION
aws s3api put-public-access-block --bucket $BUCKET \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

# Instance role with access to exactly this bucket
aws iam create-role --role-name hamstrack-ec2 --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam put-role-policy --role-name hamstrack-ec2 --policy-name attachments-s3 --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    {\"Effect\":\"Allow\",\"Action\":[\"s3:PutObject\",\"s3:GetObject\",\"s3:DeleteObject\"],\"Resource\":\"arn:aws:s3:::$BUCKET/*\"},
    {\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::$BUCKET\"}
  ]}"
aws iam create-instance-profile --instance-profile-name hamstrack-ec2
aws iam add-role-to-instance-profile --instance-profile-name hamstrack-ec2 --role-name hamstrack-ec2
aws ec2 associate-iam-instance-profile --region $REGION --instance-id $INSTANCE_ID \
  --iam-instance-profile Name=hamstrack-ec2

# CRITICAL: the app resolves credentials from IMDS *inside a container* —
# the default metadata hop limit of 1 blocks that. Raise it to 2:
aws ec2 modify-instance-metadata-options --region $REGION --instance-id $INSTANCE_ID \
  --http-put-response-hop-limit 2 --http-tokens required
```

### Server side (SSH, after the role is attached)

```bash
# migrate existing local files (volume path may differ — check `docker volume inspect hamstrack_attachments_data`)
sudo aws s3 sync /var/lib/docker/volumes/hamstrack_attachments_data/_data s3://hamstrack-attachments-prod/

# switch the app over
cd /opt/hamstrack
sudo sed -i 's/^STORAGE_TYPE=local/STORAGE_TYPE=s3/' .env
echo 'STORAGE_S3_BUCKET=hamstrack-attachments-prod' | sudo tee -a .env
echo 'STORAGE_S3_REGION=eu-north-1' | sudo tee -a .env
docker compose -f docker-compose.prod.yml up -d app

# verify: upload + download an attachment in the UI, then
aws s3 ls s3://hamstrack-attachments-prod/ --recursive | head
```

No compose change needed — `docker-compose.prod.yml` already passes
`STORAGE_*` through, and empty S3 keys fall back to the SDK default chain
(instance role). The `attachments_data` volume can be removed once verified.

## 2. Cloudflare proxy (orange cloud)

Dashboard only, ~2 minutes. Order matters:

1. **SSL/TLS → Overview → Full (strict)** — do this FIRST. The default
   Flexible mode sends plain HTTP to origin port 80 and loops on Caddy's
   HTTPS redirect.
2. **DNS → Records** — switch `@` and `www` from "DNS only" to **Proxied**.
3. **Update the Caddyfile** so the app still sees real client IPs — behind the
   proxy Caddy's peer is a Cloudflare edge node, and (Caddy ≥ 2.5) it discards
   the `X-Forwarded-For` Cloudflare sends because CF isn't a trusted proxy.
   **Without this step the auth rate limiter would bucket all visitors under a
   handful of CF IPs → false 429s for everyone.** In `/opt/hamstrack/Caddyfile`:

   ```caddy
   {
       servers {
           trusted_proxies cloudflare   # requires the cloudflare-ip plugin, OR:
           # trusted_proxies static 173.245.48.0/20 103.21.244.0/22 ... (https://www.cloudflare.com/ips/)
       }
   }

   hamstrack.com, www.hamstrack.com {
       reverse_proxy app:8080
   }
   ```

   Then `docker compose -f docker-compose.prod.yml restart caddy` and verify
   login still works and `docker compose logs app` shows distinct client IPs.
4. Certificate renewal: Let's Encrypt HTTP-01 keeps working through the proxy
   (CF passes `/.well-known/acme-challenge/`); nothing to change.

Rollback: flip records back to "DNS only".

## 3. Close SSH port 22 (deploy via SSM)

The instance role from step 1 is a prerequisite.

### AWS side (CloudShell)

```bash
REGION=eu-north-1
# 1. Let the instance register with SSM (agent is preinstalled on AL2023)
aws iam attach-role-policy --role-name hamstrack-ec2 \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
# wait a few minutes, then confirm it shows up:
aws ssm describe-instance-information --region $REGION

# 2. Deploy principal for GitHub Actions (simple variant: IAM user + keys;
#    OIDC federation is the cleaner long-term option)
aws iam create-user --user-name hamstrack-deploy
aws iam put-user-policy --user-name hamstrack-deploy --policy-name ssm-deploy --policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect":"Allow","Action":"ssm:SendCommand","Resource":[
      "arn:aws:ec2:eu-north-1:*:instance/<INSTANCE_ID>",
      "arn:aws:ssm:eu-north-1::document/AWS-RunShellScript"]},
    {"Effect":"Allow","Action":["ssm:GetCommandInvocation"],"Resource":"*"}
  ]}'
aws iam create-access-key --user-name hamstrack-deploy   # → GitHub secrets
```

GitHub repo secrets: add `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`AWS_INSTANCE_ID`; the old `SERVER_*` secrets become obsolete.

### Pipeline change (`.github/workflows/pipeline.yml`, deploy job)

```yaml
  deploy:
    needs: build-and-push
    runs-on: ubuntu-latest
    steps:
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: eu-north-1
      - name: Deploy via SSM
        run: |
          CMD_ID=$(aws ssm send-command \
            --instance-ids "${{ secrets.AWS_INSTANCE_ID }}" \
            --document-name AWS-RunShellScript \
            --parameters 'commands=["cd /opt/hamstrack && docker compose -f docker-compose.prod.yml pull && docker compose -f docker-compose.prod.yml up -d --remove-orphans && docker image prune -f"]' \
            --query 'Command.CommandId' --output text)
          aws ssm wait command-executed --command-id "$CMD_ID" \
            --instance-id "${{ secrets.AWS_INSTANCE_ID }}"
          aws ssm get-command-invocation --command-id "$CMD_ID" \
            --instance-id "${{ secrets.AWS_INSTANCE_ID }}" \
            --query '{status:Status,out:StandardOutputContent,err:StandardErrorContent}'

```

Deploy this change and verify one green deploy via SSM **before** the last step:

```bash
# 3. Remove the world-open SSH rule (or restrict to your own IP)
SG_ID=$(aws ec2 describe-instances --region eu-north-1 --instance-ids <INSTANCE_ID> \
  --query 'Reservations[0].Instances[0].SecurityGroups[0].GroupId' --output text)
aws ec2 revoke-security-group-ingress --region eu-north-1 --group-id $SG_ID \
  --protocol tcp --port 22 --cidr 0.0.0.0/0
# optional replacement for personal access:
# aws ec2 authorize-security-group-ingress --group-id $SG_ID --protocol tcp --port 22 --cidr <YOUR_IP>/32
```

Ad-hoc shell access afterwards: `aws ssm start-session --target <INSTANCE_ID>`
(or the browser-based Session Manager in the console) — no open ports needed.
