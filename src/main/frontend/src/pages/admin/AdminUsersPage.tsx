import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminUsers } from '../../api'
import { useAuthStore } from '../../auth'
import type { AdminUser } from '../../types'
import { Avatar, Button, Input, Select, ToneBadge, type BadgeTone } from '../../components/ui'
import { Pager } from '../../components/Pager'
import { AdminTable, Modal, PageHeader } from './common'

const ROLE_LABEL: Record<AdminUser['systemRole'], string> = { ADMIN: 'Admin', USER: 'User' }

/**
 * **A state, not a stored hue** — so the badge is a `ToneBadge` and this map
 * names a declared fill/ink pair rather than a colour.
 *
 * It used to hold `'var(--color-success)'` and hand it to `Badge`, whose `color`
 * means "a hue that came from the database". Two thirds of that never worked: the
 * tint and the hairline were built as `var(--color-success)20` / `…40`, which no
 * browser parses, so both were silently dropped and only the label was ever
 * green. HD-176 made the label derived too, the token failed to parse there as
 * well, and the page's four badges all fell back to neutral — visibly for
 * `ACTIVE`, `PENDING` and `ADMIN`. **`DISABLED` did not change at all**, because
 * the token it asked for is `--color-text-muted`, which holds
 * `--color-text-secondary`'s value, which is what the neutral fallback is: it was
 * the one badge whose bug could not be seen, and it is the reason a colour and a
 * fallback that agree is not evidence the mechanism is right.
 *
 * Nothing on this page is admin-chosen, so nothing here belongs on the
 * render-time path (ADR-0029).
 */
const STATUS_TONE: Record<AdminUser['status'], BadgeTone> = {
  ACTIVE: 'success',
  PENDING: 'warning',
  DISABLED: 'neutral',
}

/**
 * User directory. Accounts are created without a password or email — the admin
 * receives a one-time setup link to hand over. On DC this is the only way to
 * onboard users (public self-registration is closed). Role/status toggles are
 * blocked for the admin's own account to avoid a lock-out.
 */
export default function AdminUsersPage() {
  const qc = useQueryClient()
  const me = useAuthStore(s => s.user)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(50)
  const { data, isLoading } = useQuery({
    queryKey: ['admin', 'users', page, size],
    queryFn: () => adminUsers.list({ page, size }),
  })
  const users = data?.content ?? []
  useEffect(() => { setPage(0) }, [size])
  const [showCreate, setShowCreate] = useState(false)
  const [linkModal, setLinkModal] = useState<{ email: string; link: string } | null>(null)
  const [error, setError] = useState('')

  const invalidate = () => qc.invalidateQueries({ queryKey: ['admin', 'users'] })

  const update = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: { systemRole?: 'ADMIN' | 'USER'; status?: 'ACTIVE' | 'DISABLED' } }) =>
      adminUsers.update(id, payload),
    onSuccess: () => { setError(''); invalidate() },
    onError: e => setError(e instanceof Error ? e.message : 'Update failed'),
  })

  const regen = useMutation({
    mutationFn: (u: AdminUser) => adminUsers.regenerateSetupLink(u.id).then(r => ({ email: u.email, link: r.setupLink })),
    onSuccess: setLinkModal,
    onError: e => setError(e instanceof Error ? e.message : 'Could not generate a link'),
  })

  return (
    <>
      <PageHeader
        title="Users"
        subtitle="Create accounts and hand over a one-time setup link — no email is sent. Self-registration is closed on self-hosted installs."
        action={<Button variant="primary" onClick={() => { setError(''); setShowCreate(true) }}>+ New user</Button>}
      />

      {error && <p className="text-xs mb-3" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}

      {isLoading ? (
        <p className="mono text-sm py-8" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
      ) : (
        <AdminTable headers={['Name', 'Email', 'Role', 'Status', '']}>
          {users.map(u => {
            const isSelf = u.id === me?.id
            return (
              <tr key={u.id} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
                <td className="px-3 py-2.5">
                  <span className="inline-flex items-center gap-2 text-sm">
                    <Avatar name={u.displayName} size={22} />
                    {u.displayName}
                    {isSelf && <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>(you)</span>}
                  </span>
                </td>
                <td className="px-3 py-2.5">
                  <span className="mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>{u.email}</span>
                </td>
                <td className="px-3 py-2.5">
                  <ToneBadge label={ROLE_LABEL[u.systemRole]}
                             tone={u.systemRole === 'ADMIN' ? 'brand' : 'neutral'} />
                </td>
                <td className="px-3 py-2.5">
                  <span className="inline-flex items-center gap-1.5">
                    <ToneBadge label={u.status.charAt(0) + u.status.slice(1).toLowerCase()} tone={STATUS_TONE[u.status]} />
                    {!u.hasPassword && (
                      <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>setup pending</span>
                    )}
                  </span>
                </td>
                <td className="px-3 py-2.5 text-right whitespace-nowrap">
                  <Button variant="ghost" size="sm" loading={regen.isPending && regen.variables?.id === u.id}
                          onClick={() => regen.mutate(u)}>
                    Setup link
                  </Button>
                  {!isSelf && (
                    <>
                      <Button variant="ghost" size="sm"
                              onClick={() => update.mutate({ id: u.id, payload: { systemRole: u.systemRole === 'ADMIN' ? 'USER' : 'ADMIN' } })}>
                        {u.systemRole === 'ADMIN' ? 'Make user' : 'Make admin'}
                      </Button>
                      <Button variant="ghost" size="sm"
                              style={u.status === 'DISABLED' ? undefined : { color: 'var(--color-error-ink)' }}
                              onClick={() => update.mutate({ id: u.id, payload: { status: u.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED' } })}>
                        {u.status === 'DISABLED' ? 'Enable' : 'Disable'}
                      </Button>
                    </>
                  )}
                </td>
              </tr>
            )
          })}
        </AdminTable>
      )}

      {data && data.totalElements > 0 && (
        <Pager
          page={page}
          size={size}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          onPage={setPage}
          onSize={setSize}
        />
      )}

      {showCreate && (
        <CreateUserForm
          onClose={() => setShowCreate(false)}
          onCreated={result => { setShowCreate(false); invalidate(); setLinkModal(result) }}
        />
      )}

      {linkModal && <SetupLinkModal email={linkModal.email} link={linkModal.link} onClose={() => setLinkModal(null)} />}
    </>
  )
}

function CreateUserForm({ onClose, onCreated }: {
  onClose: () => void
  onCreated: (r: { email: string; link: string }) => void
}) {
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState<'ADMIN' | 'USER'>('USER')
  const [error, setError] = useState('')

  const create = useMutation({
    mutationFn: () => adminUsers.create({ email: email.trim(), displayName: displayName.trim(), systemRole: role }),
    onSuccess: r => onCreated({ email: r.user.email, link: r.setupLink }),
    onError: e => setError(e instanceof Error ? e.message : 'Could not create the account'),
  })

  return (
    <Modal title="New user" onClose={onClose}>
      <div className="flex flex-col gap-3">
        <Input label="Display name" value={displayName} onChange={e => setDisplayName(e.target.value)}
               placeholder="Jane Doe" autoFocus />
        <Input label="Email" type="email" value={email} onChange={e => setEmail(e.target.value)}
               placeholder="jane@company.com" />
        <Select label="Role" value={role} onChange={e => setRole(e.target.value as 'ADMIN' | 'USER')}>
          <option value="USER">User</option>
          <option value="ADMIN">Admin</option>
        </Select>
        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          No email is sent. You'll get a one-time setup link to share with the person so they can choose a password.
        </p>
        {error && <p className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!email.trim() || !displayName.trim()} loading={create.isPending}
                  onClick={() => create.mutate()}>
            Create user
          </Button>
        </div>
      </div>
    </Modal>
  )
}

function SetupLinkModal({ email, link, onClose }: { email: string; link: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(link)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch { /* clipboard blocked — the field is selectable as a fallback */ }
  }

  return (
    <Modal title="Setup link" onClose={onClose}>
      <div className="flex flex-col gap-3">
        <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          Send this one-time link to <strong>{email}</strong> so they can set their password. It expires in 7 days.
        </p>
        <div className="rounded-lg border p-2.5 mono text-xs break-all"
             style={{ background: 'var(--color-surface-2)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
          {link}
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={copy}>{copied ? 'Copied ✓' : 'Copy link'}</Button>
          <Button variant="primary" onClick={onClose}>Done</Button>
        </div>
      </div>
    </Modal>
  )
}
