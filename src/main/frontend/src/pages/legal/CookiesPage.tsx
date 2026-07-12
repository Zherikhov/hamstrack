import { Link } from 'react-router'
import LegalLayout from './LegalLayout'

// Draft policy — must be reviewed by a lawyer before public launch.
// The cookie table must stay in sync with AuthService (refresh_token cookie)
export default function CookiesPage() {
  return (
    <LegalLayout title="Cookie Policy" lastUpdated="2026-07-12">
      <p>
        Hamstrack uses only strictly necessary cookies — the minimum required for signing in to
        work. There are no analytics, advertising, or third-party cookies, and no cross-site
        tracking of any kind.
      </p>
      <p>
        Because these cookies are strictly necessary for the Service to function, they do not
        require consent under the EU ePrivacy rules. We show a one-time notice for transparency.
      </p>

      <h2>Cookies we set</h2>
      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Purpose</th>
            <th>Scope</th>
            <th>Lifetime</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>refresh_token</td>
            <td>
              Keeps you signed in — used to issue a new access token when the current one
              expires. HTTP-only (not readable by scripts), SameSite=Strict.
            </td>
            <td>/api/auth</td>
            <td>30 days</td>
          </tr>
        </tbody>
      </table>

      <h2>Browser storage we use</h2>
      <p>
        Besides cookies, the app stores a small amount of data in your browser. This data never
        leaves your device by itself:
      </p>
      <ul>
        <li>
          <strong>sessionStorage</strong> — your short-lived access token, cleared when the
          browser tab is closed.
        </li>
        <li>
          <strong>localStorage</strong> — UI preferences and a flag remembering that you dismissed
          the cookie notice.
        </li>
      </ul>

      <h2>Managing cookies</h2>
      <p>
        You can delete or block cookies in your browser settings at any time. Blocking the
        refresh_token cookie will not break browsing, but you will be signed out whenever your
        access token expires.
      </p>

      <h2>More information</h2>
      <p>
        How we handle personal data is described in the <Link to="/privacy">Privacy Policy</Link>.
        Questions: <a href="mailto:support@hamstrack.com">support@hamstrack.com</a>.
      </p>
    </LegalLayout>
  )
}
