import { proctorApi } from '@/services/proctorApi'
import type { ProctorSignalInput, SubmitSignalResponse } from '@/types/proctor'

export interface ProctorSignalClientOptions {
  attemptId: string
}

export interface ProctorSignalClientHandle {
  submit: (input: ProctorSignalInput) => Promise<SubmitSignalResponse>
  dispose: () => void
}

const newSignalId = (): string =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`

export function createProctorSignalClient(options: ProctorSignalClientOptions): ProctorSignalClientHandle {
  let disposed = false

  const submit = async (input: ProctorSignalInput): Promise<SubmitSignalResponse> => {
    if (disposed) throw new Error('Proctor signal client has been disposed.')
    const response = await proctorApi.submitSignal(options.attemptId, {
      ...input,
      signalId: input.signalId || newSignalId(),
      occurredAt: input.occurredAt ?? new Date().toISOString(),
      source: input.source ?? 'BROWSER',
    })
    return response.data.data
  }

  const dispose = (): void => {
    disposed = true
  }

  return { submit, dispose }
}

export async function submitProctorSignal(attemptId: string, input: ProctorSignalInput): Promise<SubmitSignalResponse> {
  return createProctorSignalClient({ attemptId }).submit(input)
}