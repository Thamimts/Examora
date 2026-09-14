'use client'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { BarChart3, Map } from 'lucide-react'
import { Header } from '@/components/shared'
import { ExamSpecificCoach } from '@/features/analytics/ExamSpecificCoach'
import { StudentStudyRoadmap } from '@/features/analytics/StudentStudyRoadmap'

const tabs = [
  { id: 'performance', label: 'Performance', icon: BarChart3 },
  { id: 'roadmap', label: 'Study Roadmap', icon: Map },
] as const

type TabId = (typeof tabs)[number]['id']

export function StudentAICoach() {
  const [searchParams] = useSearchParams()
  const requestedTab = searchParams.get('tab') === 'roadmap' ? 'roadmap' : 'performance'
  const [tab, setTab] = useState<TabId>(requestedTab)
  const requestedExam = searchParams.get('exam')

  useEffect(() => {
    if (requestedExam && requestedTab === 'performance' && tab !== 'performance') setTab('performance')
  }, [requestedExam, requestedTab, tab])

  return (
    <>
      <Header
        title="AI Study Coach"
        description="Pick one completed exam, then get a coach that explains and advises on only that exam."
      />
      <div role="tablist" aria-label="AI study coach sections" className="mb-6 flex flex-wrap gap-1 border-b border-border">
        {tabs.map(item => {
          const active = tab === item.id
          return (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => setTab(item.id)}
              className={`flex items-center gap-2 rounded-t-xl border-b-2 px-4 py-2.5 text-sm font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                active ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:bg-muted hover:text-foreground'
              }`}
            >
              <item.icon size={16} className={active ? 'text-primary' : ''} />
              {item.label}
            </button>
          )
        })}
      </div>
      {tab === 'performance' ? <ExamSpecificCoach /> : <StudentStudyRoadmap />}
    </>
  )
}
