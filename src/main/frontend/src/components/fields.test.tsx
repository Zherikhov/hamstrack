import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { FieldInput } from './fields'
import type { ProjectField } from '../types'

// FieldInput is the config-driven type-dispatch for custom fields — the create
// modal, side panel and backlog table all render exclusively from it. These
// assert the switch picks the right control per FieldType and reports edits
// back through onChange (undefined = cleared).

function field(over: Partial<ProjectField> & Pick<ProjectField, 'type'>): ProjectField {
  return {
    id: 'f1',
    key: 'f1',
    name: 'My Field',
    config: null,
    required: false,
    showOnCreate: true,
    ...over,
  }
}

describe('FieldInput type dispatch', () => {
  it('renders a text input for TEXT and marks required fields with *', () => {
    render(
      <FieldInput field={field({ type: 'TEXT', required: true })}
                  value={undefined} onChange={() => {}} />,
    )
    const input = screen.getByLabelText('My Field *') as HTMLInputElement
    expect(input).toBeInTheDocument()
    expect(input.type).toBe('text')
  })

  it('renders a URL text input with an https placeholder', () => {
    render(
      <FieldInput field={field({ type: 'URL' })} value={undefined} onChange={() => {}} />,
    )
    const input = screen.getByLabelText('My Field') as HTMLInputElement
    expect(input.placeholder).toContain('https')
  })

  it('renders a number input for NUMBER and emits a numeric value on change', async () => {
    const onChange = vi.fn()
    render(
      <FieldInput field={field({ type: 'NUMBER' })} value={undefined} onChange={onChange} />,
    )
    const input = screen.getByLabelText('My Field') as HTMLInputElement
    expect(input.type).toBe('number')

    await userEvent.type(input, '7')
    expect(onChange).toHaveBeenLastCalledWith(7)
    // NUMBER emits a number, not a string.
    const lastCall = onChange.mock.calls[onChange.mock.calls.length - 1]
    expect(typeof lastCall[0]).toBe('number')
  })

  it('renders a date input for DATE', () => {
    render(
      <FieldInput field={field({ type: 'DATE' })} value="2026-01-02" onChange={() => {}} />,
    )
    const input = screen.getByLabelText('My Field') as HTMLInputElement
    expect(input.type).toBe('date')
    expect(input.value).toBe('2026-01-02')
  })

  it('renders a checkbox for CHECKBOX and toggles through onChange', async () => {
    const onChange = vi.fn()
    render(
      <FieldInput field={field({ type: 'CHECKBOX', description: 'Enabled?' })}
                  value={false} onChange={onChange} />,
    )
    const box = screen.getByRole('checkbox') as HTMLInputElement
    expect(box.checked).toBe(false)

    await userEvent.click(box)
    expect(onChange).toHaveBeenLastCalledWith(true)
  })

  it('clears a TEXT value to undefined when emptied', async () => {
    const onChange = vi.fn()
    render(
      <FieldInput field={field({ type: 'TEXT' })} value="x" onChange={onChange} />,
    )
    const input = screen.getByLabelText('My Field') as HTMLInputElement
    await userEvent.clear(input)
    expect(onChange).toHaveBeenLastCalledWith(undefined)
  })
})
