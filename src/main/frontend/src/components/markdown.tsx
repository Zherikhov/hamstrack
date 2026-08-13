import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Bold, Italic, Code, Link2, List } from 'lucide-react'

// Markdown rendering for user-authored issue bodies (HD-66). SECURITY: bodies are
// untrusted user input, so the render path is deliberately locked down:
//  - NO rehype-raw → raw HTML in a body is never rendered as HTML (react-markdown
//    ignores/escapes it). This is the primary XSS defense — do not add rehype-raw.
//  - Links go through isSafeHref (http/https/mailto only; react-markdown's own
//    urlTransform already strips javascript:/data:, this is defense-in-depth) and
//    always get target=_blank + rel=noopener noreferrer.
//  - Images are disabled (returns null) — avoids remote tracking pixels / SSRF-
//    adjacent fetches from arbitrary URLs in v1.
function isSafeHref(href?: string): boolean {
  if (!href) return false
  try {
    const u = new URL(href, window.location.origin)
    return u.protocol === 'http:' || u.protocol === 'https:' || u.protocol === 'mailto:'
  } catch {
    return false
  }
}

const components: Components = {
  a({ href, children }) {
    if (!isSafeHref(href)) return <>{children}</>
    // nofollow/ugc: bodies are untrusted user content and issue trackers attract
    // link spam — deny it SEO/reputation benefit. noopener/noreferrer guards
    // reverse-tabnabbing on target=_blank.
    return <a href={href} target="_blank" rel="noopener noreferrer nofollow ugc">{children}</a>
  },
  img() {
    return null
  },
}

/** Render a Markdown string. The wrapper `.markdown-body` scopes the styling. */
export function Markdown({ children }: { children: string }) {
  return (
    <div className="markdown-body">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  )
}

// ── Lightweight formatting toolbar ──────────────────────────────────────────
// Wraps the current textarea selection in Markdown syntax. Buttons use
// onMouseDown+preventDefault so the textarea keeps focus and its selection stays
// valid while we mutate the value.

function ToolButton({ title, onClick, children }: { title: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      title={title}
      onMouseDown={e => { e.preventDefault(); onClick() }}
      className="p-1 rounded cursor-pointer hover:bg-[var(--color-surface-2)] transition-colors"
      style={{ color: 'var(--color-text-muted)' }}
    >
      {children}
    </button>
  )
}

export function MarkdownToolbar({ textareaRef, value, onChange }: {
  textareaRef: React.RefObject<HTMLTextAreaElement | null>
  value: string
  onChange: (v: string) => void
}) {
  function apply(fn: (sel: string, start: number, end: number) => { text: string; selStart: number; selEnd: number }) {
    const ta = textareaRef.current
    const start = ta?.selectionStart ?? value.length
    const end = ta?.selectionEnd ?? value.length
    const { text, selStart, selEnd } = fn(value.slice(start, end), start, end)
    onChange(text)
    requestAnimationFrame(() => {
      ta?.focus()
      ta?.setSelectionRange(selStart, selEnd)
    })
  }

  const surround = (token: string) => apply((sel, start, end) => {
    const text = value.slice(0, start) + token + sel + token + value.slice(end)
    const selStart = start + token.length
    return { text, selStart, selEnd: selStart + sel.length }
  })

  const link = () => apply((sel, start, end) => {
    const label = sel || 'text'
    const text = value.slice(0, start) + `[${label}](url)` + value.slice(end)
    const urlStart = start + label.length + 3   // position of "url" inside [label](url)
    return { text, selStart: urlStart, selEnd: urlStart + 3 }
  })

  const bulletList = () => apply((_sel, start, end) => {
    const lineStart = value.lastIndexOf('\n', start - 1) + 1
    const block = value.slice(lineStart, end)
    const prefixed = block.split('\n').map(l => (l ? `- ${l}` : l)).join('\n')
    const text = value.slice(0, lineStart) + prefixed + value.slice(end)
    return { text, selStart: lineStart, selEnd: lineStart + prefixed.length }
  })

  return (
    <div className="flex items-center gap-0.5">
      <ToolButton title="Bold" onClick={() => surround('**')}><Bold size={13} /></ToolButton>
      <ToolButton title="Italic" onClick={() => surround('*')}><Italic size={13} /></ToolButton>
      <ToolButton title="Inline code" onClick={() => surround('`')}><Code size={13} /></ToolButton>
      <ToolButton title="Link" onClick={link}><Link2 size={13} /></ToolButton>
      <ToolButton title="Bulleted list" onClick={bulletList}><List size={13} /></ToolButton>
    </div>
  )
}
