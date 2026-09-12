'use client'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { BarChart3, Map } from 'lucide-react'
import { Header } from '@/components/shared'
import { StudentAIAnalysis } from '@/features/analytics/StudentAIAnalysis'
import { StudentStudyRoadmap } from '@/features/analytics/StudentStudyRoadmap'

const tabs = [
  { id: 'performance', label: 'Performance', icon: BarChart3 },
  { id: 'roadmap', label: 'Study Roadmap', icon: Map },
] as const

type TabId = (typeof tabs)[number]['id']

export function StudentAICoach() {
  const [searchParams] = useSearchParams()
  const [tab, setTab] = useState<TabId>(searchParams.get('tab') === 'roadmap' ? 'roadmap' : 'performance')

  return (
    <>
      <Header
        title="AI Study Coach"
        description="Personalized performance insights and a step-by-step study roadmap built from your real exam results."
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
      {tab === 'performance' ? <StudentAIAnalysis /> : <StudentStudyRoadmap />}
    </>
  )
}