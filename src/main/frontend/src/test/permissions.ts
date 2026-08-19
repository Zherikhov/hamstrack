import { PROJECT_PERMISSIONS, WORKSPACE_PERMISSIONS } from '../hooks/usePermissions'

/**
 * Permission-set fixtures for the built-in roles (HD-123 §7), so a test fixture
 * says **which actor it is** rather than listing 20 strings.
 *
 * These reproduce what the server sends in `myPermissions`, including the two
 * details that are easy to get wrong and that the SPA must handle:
 *
 *  • `comment.edit` is **own-required** — it is never granted unrestricted at any
 *    role (§17.3), so it appears as `comment.edit:own` even for a project admin.
 *  • A workspace Owner/Admin's project grants are the **narrow curator set**
 *    (`project.curate.all` → project.edit / component.manage / version.manage /
 *    sprint.manage), NOT every project permission — that bypass is deliberately
 *    narrower than a project admin's (§7.1), and a fixture that pretended
 *    otherwise would let a UI gate look correct while being a notch too loose.
 */

const OWN = ':own'

/** Project **Admin** — every project permission, `comment.edit` own-only. */
export const PROJECT_ADMIN_PERMISSIONS: string[] = PROJECT_PERMISSIONS.map(
  p => (p === 'comment.edit' ? p + OWN : p),
)

/** Project **Contributor** — the everyday role: files, edits, moves, comments. */
export const PROJECT_CONTRIBUTOR_PERMISSIONS: string[] = [
  'issue.create', 'issue.edit', 'issue.transition', 'issue.assign', 'issue.assignable',
  'issue.rank', 'issue.delete' + OWN,
  'comment.create', 'comment.edit' + OWN, 'comment.delete' + OWN,
  'attachment.create', 'attachment.delete' + OWN,
  'sprint.assign',
]

/** Project **Commenter** — may discuss and attach, may not change the issue. */
export const PROJECT_COMMENTER_PERMISSIONS: string[] = [
  'comment.create', 'comment.edit' + OWN, 'comment.delete' + OWN,
  'attachment.create', 'attachment.delete' + OWN,
]

/** Project **Viewer** — reads everything, writes nothing. */
export const PROJECT_VIEWER_PERMISSIONS: string[] = []

/**
 * What a workspace Owner/Admin holds **in a project they are not a member of**:
 * the curator bypass only. The server unions this into the project response.
 */
export const PROJECT_CURATOR_BYPASS_PERMISSIONS: string[] = [
  'project.edit', 'component.manage', 'version.manage', 'sprint.manage',
]

/** Workspace **Owner/Admin** — everything except `project.administer.all`. */
export const WORKSPACE_ADMIN_PERMISSIONS: string[] = WORKSPACE_PERMISSIONS
  .filter(p => p !== 'project.administer.all')

/** Workspace **Member** — may create labels and curate the ones they created. */
export const WORKSPACE_MEMBER_PERMISSIONS: string[] = ['label.create', 'label.manage' + OWN]
