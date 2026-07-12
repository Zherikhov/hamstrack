import { Link } from 'react-router'
import LegalLayout from './LegalLayout'

// Draft terms — must be reviewed by a lawyer before public launch
export default function TermsPage() {
  return (
    <LegalLayout title="Terms of Service" lastUpdated="2026-07-12">
      <p>
        These Terms of Service ("Terms") govern your use of the hosted Hamstrack service available
        at hamstrack.com (the "Service"). By creating an account or using the Service, you agree
        to these Terms. If you do not agree, do not use the Service.
      </p>

      <h2>1. The Service</h2>
      <p>
        Hamstrack is a task tracker: it lets you create workspaces and projects, plan and track
        issues on boards and backlogs, comment, and attach files. The Service is provided by the
        operator of hamstrack.com ("we", "us").
      </p>
      <p>
        Hamstrack is open-source software. These Terms apply only to the hosted Service at
        hamstrack.com. Self-hosted installations of Hamstrack are governed by the software license
        in the source repository, not by these Terms.
      </p>

      <h2>2. Accounts</h2>
      <ul>
        <li>You must provide a valid email address and keep your account information accurate.</li>
        <li>You must be at least 16 years old to use the Service.</li>
        <li>
          You are responsible for safeguarding your password and for all activity under your
          account. Notify us promptly if you suspect unauthorized access.
        </li>
      </ul>

      <h2>3. Acceptable use</h2>
      <p>You agree not to:</p>
      <ul>
        <li>use the Service for any unlawful purpose or to store unlawful content;</li>
        <li>upload malware or content that infringes the rights of others;</li>
        <li>attempt to gain unauthorized access to the Service, other accounts, or other workspaces;</li>
        <li>interfere with or disrupt the Service, or place unreasonable load on it;</li>
        <li>resell or redistribute the Service without our permission.</li>
      </ul>

      <h2>4. Your content</h2>
      <p>
        You retain ownership of everything you create or upload in the Service — workspaces,
        projects, issues, comments, and attachments ("Content"). You grant us a limited license to
        host, store, display, and transmit your Content solely as needed to operate the Service.
        We do not use your Content for advertising and do not sell it.
      </p>
      <p>
        You are responsible for the Content you and your workspace members submit. We may remove
        Content that violates these Terms or applicable law.
      </p>

      <h2>5. Availability and changes</h2>
      <p>
        The Service is provided "as is" and "as available", without warranties of any kind and
        without a guaranteed service level (SLA). We may modify, suspend, or discontinue the
        Service or any feature at any time. Where reasonably possible, we will give notice of
        material changes.
      </p>

      <h2>6. Termination</h2>
      <p>
        You may stop using the Service and request deletion of your account at any time. We may
        suspend or terminate your account if you materially breach these Terms or if required by
        law. Upon termination, your right to use the Service ceases; we will delete or anonymize
        your personal data as described in the <Link to="/privacy">Privacy Policy</Link>.
      </p>

      <h2>7. Limitation of liability</h2>
      <p>
        To the maximum extent permitted by law, we are not liable for any indirect, incidental,
        special, consequential, or punitive damages, or for loss of data, profits, or business,
        arising out of or related to your use of the Service.
      </p>

      <h2>8. Changes to these Terms</h2>
      <p>
        We may update these Terms from time to time. We will post the updated version on this page
        and update the "Last updated" date. Continued use of the Service after changes take effect
        constitutes acceptance of the new Terms.
      </p>

      <h2>9. Governing law</h2>
      <p>
        These Terms are governed by the laws of the operator's jurisdiction, without regard to
        conflict-of-law rules. {/* TODO: set the concrete jurisdiction before public launch */}
      </p>

      <h2>10. Contact</h2>
      <p>
        Questions about these Terms: <a href="mailto:support@hamstrack.com">support@hamstrack.com</a>.
      </p>
    </LegalLayout>
  )
}
