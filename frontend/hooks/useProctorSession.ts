import { useEffect, useState } from 'react'
import { createProctorClient, type ProctoringStatus } from '@/lib/proctorClient'
import { proctorApi } from '@/services/proctorApi'

export function useProctorSession({ attemptId, active }: { attemptId: string | null; active: boolean }) {
  const [status, setStatus] = useState<ProctoringStatus>('unavailable')

  useEffect(() => {
    if (!attemptId || !active) {
      setStatus('unavailable')
      return
    }
    const client = createProctorClient({
      attemptId,
      submit: async (events) => {
        await proctorApi.events(events)
      },
      onStateChange: setStatus,
    })
    client.start()
    return () => client.dispose()
  }, [attemptId, active])

  return { status }
}