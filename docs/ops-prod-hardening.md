# Prod hardening runbook

> **Audience: the Hamstrack owner only.** This documents how the *official*
> hosted deployment at **hamstrack.com** (AWS EC2 + SSM, Cloudflare, S3) is set
> up and hardened — it is a reference specific to that infrastructure, **not a
> requirement for self-hosting**. If you're running your own instance (DC), see
> the [Self-hosting guide](self-hosting.md); you do not need any of the
> AWS/Cloudflare steps below.

Remaining PLAN.md Phase 7 backlog items that require AWS console / Cloudflare
dashboard access (no AWS credentials exist on the dev machine or the EC2
instance — these steps must be run by an account admin, e.g. in
[CloudShell](https://console.aws.amazon.com/cloudshell)). Server-side steps can
be done over SSH afterwards.

Facts used below: region `eu-north-1`, instance IP `<INSTANCE_IP>`
(`<INSTANCE_PRIVATE_DNS>`), app dir `/opt/hamstrack`, compose project `hamstrack`,
only Caddy (80/443) is exposed publicly.

---

## 1. Attachments → S3

### AWS side (CloudShell)

```bash
REGION=eu-north-1
BUCKET=<BUCKET>
INSTANCE_ID=$(aws ec2 describe-instances --region $REGION \
  --filters "Name=ip-address,Values=<INSTANCE_IP>" \
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
sudo aws s3 sync /var/lib/docker/volumes/hamstrack_attachments_data/_data s3://<BUCKET>/

# switch the app over
cd /opt/hamstrack
sudo sed -i 's/^STORAGE_TYPE=local/STORAGE_TYPE=s3/' .env
echo 'STORAGE_S3_BUCKET=<BUCKET>' | sudo tee -a .env
echo 'STORAGE_S3_REGION=eu-north-1' | sudo tee -a .env
docker compose -f docker-compose.prod.yml up -d app

# verify: upload + download an attachment in the UI, then
aws s3 ls s3://<BUCKET>/ --recursive | head
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

   # Site address comes from SITE_ADDRESS in .env — do NOT hardcode the domain.
   {$SITE_ADDRESS} {
       reverse_proxy app:8080
   }
   ```

   The global `trusted_proxies` block is the only site-specific addition here —
   keep the site line as `{$SITE_ADDRESS}` (add `SITE_ADDRESS=…` to `/opt/hamstrack/.env`
   first, since the caddy service uses `${SITE_ADDRESS:?…}` fail-fast). Merge this
   block into the existing Caddyfile; don't overwrite it with the repo template.

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

### Pipeline change (`.github/workflows/deploy.yml`)

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

> The snapshot above is the original SSH→SSM migration shape. The live
> `deploy.yml` since then is `workflow_run`-triggered (fires after a green
> `Build` on `main`) with the instance id **inlined** (not a secret), and the SSM
> command has grown — see the config auto-sync note next.

#### Config auto-sync from the repo (2026-08-06)

Originally the SSM command only pulled the app **image** (`docker compose pull`),
so the config files in `/opt/hamstrack` (`docker-compose.prod.yml`, `Caddyfile`,
`.env`, and now the observability stack) were maintained **by hand** — committing
a compose change did not put it on the server. The deploy now **downloads the
repo-owned config for the exact built commit** before bringing the stack up, so a
push of a config change ships to prod automatically. The `--parameters` command is:

```bash
--parameters 'commands=["cd /opt/hamstrack && rm -rf /opt/hamstrack/.synctmp && mkdir -p /opt/hamstrack/.synctmp && curl -fsSL https://codeload.github.com/Zherikhov/hamstrack/tar.gz/${{ github.event.workflow_run.head_sha }} | tar xz -C /opt/hamstrack/.synctmp --strip-components=1 && cp -f /opt/hamstrack/.synctmp/docker-compose.prod.yml /opt/hamstrack/.synctmp/docker-compose.observability.yml /opt/hamstrack/ && rm -rf /opt/hamstrack/observability && cp -rf /opt/hamstrack/.synctmp/observability /opt/hamstrack/observability && rm -rf /opt/hamstrack/.synctmp && docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml pull && docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml up -d --remove-orphans && docker image prune -f"]'
```

Notes:

- **Downloads by `head_sha`**, so the config always matches the image that was
  just built (atomic). `--strip-components=1` flattens the tarball's top dir
  (`hamstrack-<sha>/` — the repo was renamed easyTask→hamstrack, so never
  hardcode the prefix). Repo is **public**, so codeload needs no token; if you
  make it private, add a PAT to the URL.
- **Only three paths are synced**: `docker-compose.prod.yml`,
  `docker-compose.observability.yml`, and the whole `observability/` dir (replaced
  wholesale). **`Caddyfile` and `.env` are deliberately NOT touched** — the prod
  `Caddyfile` diverges from the repo (the manual Cloudflare `trusted_proxies`
  block, §2) and `.env` holds secrets. Both stay operator-owned.
- **Both `-f` files, always** (`docker-compose.prod.yml` +
  `docker-compose.observability.yml`) in `pull` and `up`. Never run
  `up --remove-orphans` with only the prod file once the obs stack is up — it
  would delete loki/alloy/grafana/prometheus/exporters as orphans.
- **One-time server prerequisite** (secrets can't come from the repo): set at
  least `GF_SECURITY_ADMIN_PASSWORD` in `/opt/hamstrack/.env` **before** the first
  deploy that includes the observability file, or Grafana's `${...:?}` fail-fast
  aborts the whole `up`. Optional: `OBS_ALERT_EMAIL_TO`, `PROMETHEUS_RETENTION_*`,
  `DB_MONITOR_USER`/`DB_MONITOR_PASSWORD` (read-only `pg_monitor` role for
  postgres-exporter — see the [Self-hosting guide](self-hosting.md#observability-optional)).

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

## 4. Observability — reaching Grafana over SSM

The observability stack (`docker-compose.observability.yml`: Loki + Alloy +
Grafana, Prometheus/exporters in later phases) publishes **no public port**.
Grafana binds `127.0.0.1:3000` on the instance, so it's reachable from the host's
loopback but not the internet. You tunnel to it with an SSM port-forward — the
same credentials/instance role already used for deploys, no SSH, no security-group
change.

Prerequisite on the server: the stack must be running and its config present under
`/opt/hamstrack/observability/` (see the [Self-hosting guide](self-hosting.md#observability-optional)
for the file layout). Bring it up alongside the app:

```bash
cd /opt/hamstrack
docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml up -d
```

> **Always pass BOTH `-f` files together.** Running `up --remove-orphans` with only
> `docker-compose.prod.yml` once the stack is up would treat loki/alloy/grafana as
> orphans and delete them.

Then, from your laptop (needs AWS creds with `ssm:StartSession` + the SSM plugin):

```bash
aws ssm start-session \
  --region eu-north-1 \
  --target i-019fe684b25ad831f \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'
```

Leave it running and open **http://localhost:3000** — log in with
`GF_SECURITY_ADMIN_USER` / `GF_SECURITY_ADMIN_PASSWORD` from `/opt/hamstrack/.env`.
The Loki datasource and the **Logs** dashboard are auto-provisioned; use
**Explore → Loki** for ad-hoc `{container="hamstrack-app-1"}` queries.

To debug Loki/Prometheus directly, forward their ports the same way (they don't
bind a host port, so use `AWS-StartPortForwardingSessionToRemoteHost` targeting the
container, or query them from inside Grafana).
