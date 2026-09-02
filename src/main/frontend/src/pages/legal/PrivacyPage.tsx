import { Link } from 'react-router'
import LegalLayout from './LegalLayout'

// Draft policy — must be reviewed by a lawyer before public launch
export default function PrivacyPage() {
  return (
    <LegalLayout title="Privacy Policy" lastUpdated="2026-09-02">
      <p>
        This Privacy Policy explains how the operator of hamstrack.com ("we", "us") collects and
        processes personal data when you use the hosted Hamstrack service (the "Service"). It
        applies only to the hosted Service at hamstrack.com — self-hosted installations of
        Hamstrack are operated by their own administrators, who act as the data controller for
        those installations.
      </p>

      <h2>1. Data we collect</h2>
      <ul>
        <li>
          <strong>Account data</strong> — your email address, display name, and a hash of your
          password (we never store the password itself).
        </li>
        <li>
          <strong>Content</strong> — workspaces, projects, issues, comments, and file attachments
          you or your workspace members create in the Service.
        </li>
        <li>
          <strong>Technical data</strong> — server logs including IP address, browser user agent,
          and timestamps, kept for security and troubleshooting.
        </li>
      </ul>
      <p>We do not collect data for advertising and we do not use third-party analytics or trackers.</p>

      <h2>2. How we use your data</h2>
      <ul>
        <li>to provide and operate the Service (accounts, workspaces, notifications);</li>
        <li>
          to send transactional email — account verification, password reset, and workspace
          invitations. We do not send marketing email;
        </li>
        <li>to secure the Service, prevent abuse, and diagnose problems.</li>
      </ul>

      <h2>3. Legal bases</h2>
      <p>
        Where the GDPR applies, we process account data and Content to perform our contract with
        you (Art. 6(1)(b)), and technical/log data based on our legitimate interest in keeping the
        Service secure and reliable (Art. 6(1)(f)).
      </p>

      <h2>4. Who processes your data</h2>
      <p>
        Your data is stored and processed by infrastructure providers acting on our behalf:
        hosting and database servers, S3-compatible object storage for file attachments, and an
        email (SMTP) provider for transactional messages. These processors only handle data as
        needed to run the Service.
      </p>
      <p>We do not sell personal data and do not share it with third parties for their own purposes.</p>

      {/*
        HD-193 §4. Describes what the deletion procedure DOES, in the same words the
        operator runbook implements, and stays silent on WHEN — no number of days, no
        response deadline, no lawful basis, no named entity. Those four facts are HD-192's
        and need a lawyer's input this page does not have. "within a reasonable period" is
        the existing representation, carried through byte-identical and only repositioned:
        replacing it with a number, or dropping it, would answer HD-192's question without
        HD-192.

        "Backups of the whole database are taken routinely" is load-bearing wording, not
        style. PublishedClaimsTest fails any scanned surface that carries an opening
        fragment of the canonical durability paragraph without carrying the whole of it,
        and that paragraph belongs on the Cloud promise carriers, not on this page — so
        the frequency word from its second sentence must not be echoed here. Do not
        "improve" this line into the wording that paragraph uses.

        The deletion pointer below names the Account page and NOTHING ELSE. This page is
        routed in every installation, but section 10 is the hosted operator's own support
        inbox — so a pointer to it would send a self-hoster's users, carrying their name,
        address and user id, to a mailbox nobody can service on their behalf. The Account
        page resolves the installation's own configured address, and names the local
        administrator when none is set. Do not answer this by adding a self-hosted
        paragraph here instead: these are the hosted Service's legal pages, and a DC
        carve-out on them is a second, unreviewed representation of a controller this
        operator is not.
      */}
      <h2>5. Retention</h2>
      <p>
        We keep your data for as long as your account exists. You can ask us to delete your
        account from the Account page in the app — it tells you where to write. We confirm the
        request by sending a code to the email address on the account before acting on it. We then
        delete or anonymize your personal data within a reasonable period, except where we must
        retain it to comply with legal obligations.
      </p>
      <p>
        Deletion removes the personal data in your account record — your email address, display
        name and sign-in credentials — and removes your address from our mail delivery logs. Work
        you created inside a workspace (issues, comments and uploaded files) stays with that
        workspace and is re-attributed to "Deleted user", so the team's history stays readable;
        text other members wrote is not edited, and your name may remain where they typed it. A
        workspace in which you are the only remaining member is deleted with its contents. Content
        shared into a workspace may remain visible to other members of that workspace as part of
        their data.
      </p>
      <p>
        Backups of the whole database are taken routinely and are not edited. If we ever restore
        one, we re-apply every completed deletion to the restored copy before it is used again.
      </p>

      <h2>6. Your rights</h2>
      <p>
        Depending on your jurisdiction, you may have the right to access, rectify, delete, or
        export your personal data, restrict or object to its processing, and lodge a complaint
        with a supervisory authority. To exercise these rights, contact us at{' '}
        <a href="mailto:support@hamstrack.com">support@hamstrack.com</a>.
      </p>

      <h2>7. Cookies</h2>
      <p>
        The Service uses only strictly necessary cookies. See the{' '}
        <Link to="/cookies">Cookie Policy</Link> for details.
      </p>

      <h2>8. Children</h2>
      <p>The Service is not intended for children under 16, and we do not knowingly collect their data.</p>

      <h2>9. Changes to this policy</h2>
      <p>
        We may update this policy from time to time. We will post the updated version on this page
        and update the "Last updated" date.
      </p>

      <h2>10. Contact</h2>
      <p>
        Privacy questions and requests: <a href="mailto:support@hamstrack.com">support@hamstrack.com</a>.
      </p>
    </LegalLayout>
  )
}
