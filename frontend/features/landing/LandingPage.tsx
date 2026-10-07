'use client'

import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { motion, useReducedMotion } from 'framer-motion'
import type { Variants } from 'framer-motion'
import {
  ArrowRight,
  Award,
  BarChart3,
  BookOpen,
  Check,
  FileText,
  GraduationCap,
  ListChecks,
  Lock,
  Map,
  Menu,
  ShieldCheck,
  Sparkles,
  Target,
  TrendingUp,
  Users,
  X,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { OAuthCallback } from '@/components/auth/OAuthCallback'
import { useAuthStore } from '@/store/authStore'

type NavLinkItem = { label: string; href: string }

const navLinks: NavLinkItem[] = [
  { label: 'How It Works', href: '#how-it-works' },
  { label: 'Features', href: '#features' },
  { label: 'AI Coach', href: '#ai-coach' },
  { label: 'Practice', href: '#practice' },
]

function Reveal({
  children,
  className = '',
  delay = 0,
  amount = 0.2,
  onMount = false,
}: {
  children: React.ReactNode
  className?: string
  delay?: number
  amount?: number
  onMount?: boolean
}) {
  const reduced = useReducedMotion() ?? false
  const variants: Variants = reduced
    ? { hidden: { opacity: 1, y: 0 }, show: { opacity: 1, y: 0 } }
    : {
        hidden: { opacity: 0, y: 18 },
        show: { opacity: 1, y: 0, transition: { duration: 0.55, ease: 'easeOut', delay } },
      }
  return (
    <motion.div
      className={className}
      variants={variants}
      initial="hidden"
      {...(onMount ? { animate: 'show' } : { whileInView: 'show', viewport: { once: true, amount } })}
    >
      {children}
    </motion.div>
  )
}

function Eyebrow({ children }: { children: React.ReactNode }) {
  return <p className="text-xs font-semibold uppercase tracking-widest text-primary">{children}</p>
}

function SectionHeading({
  eyebrow,
  title,
  description,
  center = false,
}: {
  eyebrow: string
  title: string
  description?: string
  center?: boolean
}) {
  return (
    <Reveal className={center ? 'mx-auto max-w-3xl text-center' : 'max-w-2xl'}>
      <Eyebrow>{eyebrow}</Eyebrow>
      <h2 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">{title}</h2>
      {description ? <p className="mt-4 text-base leading-7 text-muted-foreground">{description}</p> : null}
    </Reveal>
  )
}

const heroStages: { label: string; icon: LucideIcon }[] = [
  { label: 'Exam', icon: FileText },
  { label: 'Result', icon: Award },
  { label: 'Understanding', icon: BarChart3 },
  { label: 'AI Guidance', icon: Sparkles },
  { label: 'Practice', icon: Target },
  { label: 'Improvement', icon: TrendingUp },
]

const productSurfaces = [
  'Score & review',
  'Question-by-question',
  'Weak areas',
  'AI coach chat',
  'Study roadmap',
  'Adaptive practice',
]

function HeroVisual() {
  return (
    <div className="rounded-3xl border border-border bg-card p-5 shadow-sm sm:p-6">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm font-semibold">One exam becomes your learning path</p>
        <span className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-2.5 py-1 text-[11px] font-semibold text-primary">
          <TrendingUp size={12} />
          Exam → Improvement
        </span>
      </div>
      <div className="mt-5 grid grid-cols-2 gap-2.5 sm:grid-cols-3">
        {heroStages.map((stage, index) => (
          <div key={stage.label} className="flex items-center gap-3 rounded-xl border border-border bg-background p-3.5">
            <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-primary text-primary-foreground">
              <stage.icon size={17} />
            </span>
            <span className="min-w-0">
              <span className="block text-xs text-muted-foreground">Step {index + 1}</span>
              <span className="block truncate text-sm font-semibold">{stage.label}</span>
            </span>
          </div>
        ))}
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-border pt-4">
        <span className="text-xs font-medium text-muted-foreground">Built around:</span>
        {productSurfaces.map((surface) => (
          <span key={surface} className="rounded-full bg-muted px-3 py-1 text-xs font-medium text-muted-foreground">
            {surface}
          </span>
        ))}
      </div>
    </div>
  )
}

const problemPoints: { title: string; description: string }[] = [
  { title: 'Only a score', description: 'An exam ends and all you get is a number.' },
  { title: 'No explanation', description: 'The score never tells you what to improve.' },
  { title: 'Unknown next step', description: 'It is hard to know what you should study next.' },
  { title: 'Generic practice', description: 'Random practice questions do not target your real weaknesses.' },
  { title: 'Disconnected learning', description: 'Exam performance and everyday learning rarely feed into each other.' },
]

const learningLoopSteps: { title: string; description: string; icon: LucideIcon }[] = [
  { title: 'Take Exam', description: 'Complete a secure, timed assessment in a focused exam environment.', icon: BookOpen },
  { title: 'Understand Result', description: 'See your score and a question-by-question review of what you got right and wrong.', icon: Award },
  { title: 'Analyze Performance', description: 'Use analytics to find patterns, trends, and weak areas across your exams.', icon: BarChart3 },
  { title: 'Get AI Guidance', description: 'AI analysis turns your performance data into clear study priorities.', icon: Sparkles },
  { title: 'Follow Study Roadmap', description: 'Work through a roadmap that unfolds as you advance, from easy to medium to hard.', icon: Map },
  { title: 'Practice Weak Areas', description: 'Practice with questions chosen at a difficulty that fits your current level.', icon: Target },
  { title: 'Improve', description: 'Track progress and take your next exam with a clearer strategy.', icon: TrendingUp },
]

const howItWorks: { number: string; title: string; description: string }[] = [
  {
    number: '01',
    title: 'Take the Exam',
    description: 'Complete a structured online assessment in a focused, controlled exam environment.',
  },
  {
    number: '02',
    title: 'Understand Your Result',
    description: 'Review your score and see exactly how you performed on each question.',
  },
  {
    number: '03',
    title: 'Discover What to Improve',
    description: 'Use performance analytics and AI-powered analysis to find the areas that need attention.',
  },
  {
    number: '04',
    title: 'Study and Practice',
    description: 'Follow the guidance you were given and practice at a difficulty level that fits you.',
  },
]

const benefits: { icon: LucideIcon; title: string; description: string }[] = [
  {
    icon: BookOpen,
    title: 'Smart Online Exams',
    description: 'Take structured assessments in a focused exam environment, with clear instructions and timing.',
  },
  {
    icon: BarChart3,
    title: 'Performance Analytics',
    description: 'Understand your performance beyond a single score with breakdowns, trends, and weak areas.',
  },
  {
    icon: Sparkles,
    title: 'AI Performance Analysis',
    description: 'Get AI-generated insights and recommendations based on the performance data available to you.',
  },
  {
    icon: Map,
    title: 'AI Study Guidance',
    description: 'Turn exam insights into a clearer study direction with a structured learning roadmap.',
  },
  {
    icon: Target,
    title: 'Adaptive Practice',
    description: 'Practice with questions selected to match your performance and the difficulty you need.',
  },
  {
    icon: ListChecks,
    title: 'Question Review',
    description: 'Review completed exams and understand the result of every answer you gave.',
  },
]

const aiCoachStory: { label: string; icon: LucideIcon }[] = [
  { label: 'Performance', icon: BarChart3 },
  { label: 'AI Analysis', icon: Sparkles },
  { label: 'Study Priorities', icon: ListChecks },
  { label: 'Roadmap', icon: Map },
  { label: 'Practice', icon: Target },
]

function AICoachVisual() {
  return (
    <div className="rounded-3xl border border-border bg-card p-5 shadow-sm sm:p-6">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm font-semibold">AI Study Coach</p>
        <span className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-2.5 py-1 text-[11px] font-semibold text-primary">
          <Sparkles size={12} />
          Based on your performance
        </span>
      </div>
      <div className="mt-5 flex flex-wrap items-center gap-2">
        {aiCoachStory.map((step, index) => (
          <span key={step.label} className="flex items-center gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-xl border border-border bg-background px-3 py-2 text-xs font-medium">
              <step.icon size={13} className="text-primary" />
              {step.label}
            </span>
            {index < aiCoachStory.length - 1 ? (
              <ArrowRight size={13} className="text-muted-foreground/50" aria-hidden="true" />
            ) : null}
          </span>
        ))}
      </div>
      <div className="mt-5 space-y-2.5">
        <div className="rounded-xl border border-border bg-background p-4">
          <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">Study roadmap</p>
          <ul className="mt-3 space-y-2.5">
            <li className="flex items-center justify-between gap-3 text-sm">
              <span className="flex items-center gap-2">
                <span className="grid size-6 place-items-center rounded-md bg-emerald-500/10 text-emerald-600">
                  <Check size={13} />
                </span>
                Easy level
              </span>
              <span className="rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-semibold text-emerald-600">Complete</span>
            </li>
            <li className="flex items-center justify-between gap-3 text-sm">
              <span className="flex items-center gap-2">
                <span className="grid size-6 place-items-center rounded-md bg-primary/10 text-primary">
                  <TrendingUp size={13} />
                </span>
                Medium level
              </span>
              <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-semibold text-primary">Unlocked</span>
            </li>
            <li className="flex items-center justify-between gap-3 text-sm">
              <span className="flex items-center gap-2">
                <span className="grid size-6 place-items-center rounded-md bg-muted text-muted-foreground">
                  <Lock size={13} />
                </span>
                Hard level
              </span>
              <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-semibold text-muted-foreground">Locked</span>
            </li>
          </ul>
        </div>
        <div className="rounded-xl border border-border bg-background p-4">
          <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">What to focus on</p>
          <ul className="mt-3 space-y-2.5 text-sm">
            <li className="flex items-start gap-2.5">
              <Check size={15} className="mt-0.5 shrink-0 text-primary" />
              Your strengths are summarized from real results
            </li>
            <li className="flex items-start gap-2.5">
              <Check size={15} className="mt-0.5 shrink-0 text-primary" />
              Weak areas become study priorities
            </li>
            <li className="flex items-start gap-2.5">
              <Check size={15} className="mt-0.5 shrink-0 text-primary" />
              Recommendations point toward your next practice step
            </li>
          </ul>
        </div>
      </div>
    </div>
  )
}

const practiceLevels: { label: string; description: string; icon: LucideIcon }[] = [
  { label: 'Easy', description: 'Start from a difficulty that builds confidence on the fundamentals.', icon: BookOpen },
  { label: 'Medium', description: 'Solidify what you know with questions that stretch you a little further.', icon: Target },
  { label: 'Hard', description: 'Push yourself with harder material once the roadmap opens it up.', icon: TrendingUp },
]

const trustPoints: { icon: LucideIcon; title: string; description: string }[] = [
  { icon: ShieldCheck, title: 'Secure account access', description: 'Sign in with your own account, and your password is never stored in plain text.' },
  { icon: Users, title: 'Role-based access', description: 'Students, teachers, and administrators each see only what their role allows.' },
  { icon: FileText, title: 'Controlled, timed exams', description: 'Exams run within set start and end windows with an on-screen countdown.' },
  { icon: Check, title: 'Validated attempts', description: 'You can only start an attempt when it is open, and your work is tracked through the exam.' },
  { icon: Lock, title: 'Private results', description: 'Your results, reviews, and analytics are only visible to you and the educators running the assessment.' },
  { icon: ShieldCheck, title: 'Monitoring options', description: 'Educators can use monitoring and proctoring features to protect exam integrity.' },
]

const teacherPoints: { icon: LucideIcon; title: string; description: string }[] = [
  { icon: BookOpen, title: 'Create exams & questions', description: 'Build structured assessments with clear instructions, timing, and access control.' },
  { icon: Users, title: 'Manage assessments', description: 'Assign exams and manage who can attempt them, with results collected automatically.' },
  { icon: BarChart3, title: 'View performance', description: 'Follow class and individual performance across exams once results are submitted.' },
  { icon: ShieldCheck, title: 'Monitor exams', description: 'Use monitoring tools to keep an eye on active sessions during assessment.' },
]

function Navbar() {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const role = user?.role ?? null

  const handleGetStarted = () => navigate(user ? `/${role}` : '/register')

  return (
    <header className="sticky top-0 z-40 backdrop-blur-md">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between gap-4 px-4 sm:px-6 lg:px-8">
        <a href="#top" className="flex items-center gap-2.5">
          <span className="grid size-8 place-items-center rounded-lg bg-primary text-sm font-bold text-primary-foreground">E</span>
          <span className="text-base font-semibold tracking-tight">Examora</span>
        </a>

        <nav aria-label="Main" className="hidden items-center gap-1 md:flex">
          {navLinks.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className="rounded-lg px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              {link.label}
            </a>
          ))}
        </nav>

        <div className="hidden items-center gap-2.5 md:flex">
          {user ? (
            <a
              href={`/${role}`}
              className="rounded-xl bg-primary px-4 py-2.5 text-sm font-semibold text-primary-foreground transition-opacity hover:opacity-90"
            >
              Open workspace
            </a>
          ) : (
            <>
              <a href="/login" className="rounded-xl px-3.5 py-2.5 text-sm font-semibold transition-colors hover:bg-muted">
                Login
              </a>
              <button
                type="button"
                onClick={handleGetStarted}
                className="rounded-xl bg-primary px-4 py-2.5 text-sm font-semibold text-primary-foreground transition-opacity hover:opacity-90"
              >
                Get Started
              </button>
            </>
          )}
        </div>

        <button
          type="button"
          aria-label={open ? 'Close menu' : 'Open menu'}
          aria-expanded={open}
          onClick={() => setOpen((value) => !value)}
          className="grid size-10 place-items-center rounded-lg border border-border md:hidden"
        >
          {open ? <X size={20} /> : <Menu size={20} />}
        </button>
      </div>

      {open ? (
        <nav aria-label="Mobile" className="border-t border-border bg-background px-4 py-3 md:hidden">
          {navLinks.map((link) => (
            <a
              key={link.href}
              href={link.href}
              onClick={() => setOpen(false)}
              className="block rounded-lg px-3 py-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              {link.label}
            </a>
          ))}
          <div className="mt-2 flex flex-col gap-2 border-t border-border pt-3">
            {user ? (
              <a
                href={`/${role}`}
                onClick={() => setOpen(false)}
                className="rounded-xl bg-primary px-4 py-2.5 text-center text-sm font-semibold text-primary-foreground"
              >
                Open workspace
              </a>
            ) : (
              <>
                <a
                  href="/login"
                  onClick={() => setOpen(false)}
                  className="rounded-xl border border-border px-4 py-2.5 text-center text-sm font-semibold"
                >
                  Login
                </a>
                <button
                  type="button"
                  onClick={() => {
                    setOpen(false)
                    handleGetStarted()
                  }}
                  className="rounded-xl bg-primary px-4 py-2.5 text-sm font-semibold text-primary-foreground"
                >
                  Get Started
                </button>
              </>
            )}
          </div>
        </nav>
      ) : null}
    </header>
  )
}

function Hero() {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const role = user?.role ?? null

  return (
    <section id="top" className="relative overflow-hidden pt-16 pb-20 sm:pt-24 lg:pt-28">
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 bg-[linear-gradient(to_bottom,var(--tw-gradient-from),transparent)] from-muted/60 to-transparent"
        style={{
          backgroundImage:
            'radial-gradient(ellipse 80% 60% at 50% -10%, rgba(0,0,0,0.06), transparent)',
        }}
      />
      <div className="relative mx-auto grid max-w-7xl items-center gap-12 px-4 sm:px-6 lg:grid-cols-2 lg:gap-16 lg:px-8">
        <div>
          <Reveal onMount>
            <Eyebrow>Examora · Online exams that keep teaching you</Eyebrow>
            <h1 className="mt-4 text-4xl font-semibold leading-[1.1] tracking-tight sm:text-5xl lg:text-6xl">
              Turn every exam into your next learning step.
            </h1>
            <p className="mt-5 max-w-xl text-lg leading-8 text-muted-foreground">
              Take secure online exams, understand your results, and get AI-powered guidance on what to study and practice next.
            </p>
          </Reveal>
          <Reveal delay={0.1} onMount>
            <div className="mt-8 flex flex-wrap items-center gap-3.5">
              <button
                type="button"
                onClick={() => navigate(user ? `/${role}` : '/register')}
                className="inline-flex items-center gap-2 rounded-xl bg-primary px-6 py-3.5 text-sm font-semibold text-primary-foreground transition-opacity hover:opacity-90"
              >
                Get Started
                <ArrowRight size={16} />
              </button>
              <a
                href="#how-it-works"
                className="inline-flex items-center gap-2 rounded-xl border border-border px-6 py-3.5 text-sm font-semibold transition-colors hover:bg-muted"
              >
                Explore How It Works
              </a>
            </div>
          </Reveal>
          <Reveal delay={0.18} onMount>
            <p className="mt-6 text-sm text-muted-foreground">
              Students can also practice with difficulty-aware questions and an AI study coach.
            </p>
          </Reveal>
        </div>
        <Reveal onMount delay={0.15}>
          <HeroVisual />
        </Reveal>
      </div>
    </section>
  )
}

function Problem({ points }: { points: { title: string; description: string }[] }) {
  return (
    <section id="problem" className="py-20">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <SectionHeading
          center
          eyebrow="The problem"
          title="A score alone is not feedback."
          description="Traditional exams stop helping the moment you submit them. Everything after the grade is guesswork — until you take the next exam and repeat the same cycle."
        />
        <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
          {points.map((point, index) => (
            <Reveal key={point.title} delay={index * 0.05}>
              <div className="flex h-full flex-col rounded-2xl border border-border bg-card p-5">
                <span className="text-xs font-semibold text-muted-foreground">{String(index + 1).padStart(2, '0')}</span>
                <h3 className="mt-3 text-base font-semibold">{point.title}</h3>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{point.description}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  )
}

function Solution({ steps }: { steps: { title: string; description: string; icon: LucideIcon }[] }) {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const role = user?.role ?? null

  return (
    <section id="solution" className="bg-muted/40 py-20">
      <div className="mx-auto grid max-w-7xl gap-12 px-4 sm:px-6 lg:grid-cols-[1fr_1.1fr] lg:gap-16 lg:px-8">
        <div className="lg:sticky lg:top-28 lg:self-start">
          <SectionHeading
            eyebrow="The learning loop"
            title="Examora closes the loop between exams and learning."
            description="Every exam becomes the starting point for understanding, guidance, and practice — so the next attempt is always better informed."
          />
          <div className="mt-8">
            <button
              type="button"
              onClick={() => navigate(user ? `/${role}` : '/register')}
              className="inline-flex items-center gap-2 rounded-xl bg-primary px-6 py-3.5 text-sm font-semibold text-primary-foreground transition-opacity hover:opacity-90"
            >
              Start the loop
              <ArrowRight size={16} />
            </button>
          </div>
        </div>

        <ol className="mt-10 lg:mt-12">
          {steps.map((step, index) => (
            <li key={step.title} className="relative flex gap-5 pb-10 last:pb-0">
              {index < steps.length - 1 ? (
                <span
                  aria-hidden="true"
                  className="absolute left-[26px] top-14 h-[calc(100%-3rem)] w-px bg-border"
                />
              ) : null}
              <span className="grid size-[52px] shrink-0 place-items-center rounded-2xl border border-border bg-card shadow-sm">
                <step.icon size={22} className="text-primary" />
              </span>
              <span className="min-w-0">
                <span className="flex items-center gap-2.5">
                  <span className="text-sm font-semibold text-primary">Step {index + 1}</span>
                </span>
                <h3 className="mt-1 text-lg font-semibold">{step.title}</h3>
                <p className="mt-1.5 text-sm leading-6 text-muted-foreground">{step.description}</p>
              </span>
            </li>
          ))}
        </ol>
      </div>
    </section>
  )
}

function HowItWorks({ steps }: { steps: { number: string; title: string; description: string }[] }) {
  return (
    <section id="how-it-works" className="py-20">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <SectionHeading
          center
          eyebrow="How it works"
          title="Four steps from first exam to next learning step."
          description="The same assessment workflow works for students, educators, and independent learners."
        />
        <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {steps.map((step, index) => (
            <Reveal key={step.number} delay={index * 0.06}>
              <div className="h-full rounded-2xl border border-border bg-card p-6">
                <span className="font-mono text-sm font-semibold text-primary">{step.number}</span>
                <h3 className="mt-4 text-lg font-semibold">{step.title}</h3>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{step.description}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  )
}

function Features({ items }: { items: { icon: LucideIcon; title: string; description: string }[] }) {
  const [smartExam, ...rest] = items
  return (
    <section id="features" className="bg-muted/40 py-20">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <SectionHeading
          center
          eyebrow="Key benefits"
          title="Everything you need to learn from every exam."
          description="Every capability on this page already exists in the product and is built around a real, working learning loop."
        />
        <div className="mt-12 grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          <Reveal className="md:col-span-2 lg:col-span-2">
            <div className="flex h-full flex-col rounded-2xl border border-border bg-card p-6">
              <span className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary">
                <smartExam.icon size={20} />
              </span>
              <h3 className="mt-4 text-lg font-semibold">{smartExam.title}</h3>
              <p className="mt-2 max-w-md text-sm leading-6 text-muted-foreground">{smartExam.description}</p>
            </div>
          </Reveal>
          {rest.slice(0, 2).map((item, index) => (
            <Reveal key={item.title} delay={index * 0.06}>
              <div className="h-full rounded-2xl border border-border bg-card p-6">
                <span className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary">
                  <item.icon size={20} />
                </span>
                <h3 className="mt-4 text-lg font-semibold">{item.title}</h3>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{item.description}</p>
              </div>
            </Reveal>
          ))}
          {rest.slice(2).map((item, index) => (
            <Reveal key={item.title} delay={index * 0.06}>
              <div className="h-full rounded-2xl border border-border bg-card p-6">
                <span className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary">
                  <item.icon size={20} />
                </span>
                <h3 className="mt-4 text-lg font-semibold">{item.title}</h3>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{item.description}</p>
              </div>
            </Reveal>
          ))}
        </div>
        <Reveal delay={0.1}>
          <div className="mt-4 flex flex-col items-start gap-4 rounded-2xl border border-border bg-card p-6 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-start gap-4 sm:items-center">
              <span className="grid size-11 shrink-0 place-items-center rounded-xl bg-primary/10 text-primary">
                <ShieldCheck size={20} />
              </span>
              <span>
                <h3 className="text-lg font-semibold">Secure Assessments</h3>
                <p className="mt-1 text-sm leading-6 text-muted-foreground">
                  Role-based access, controlled exam windows, validated attempts, and monitoring options keep assessments fair and safe.
                </p>
              </span>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  )
}

function AICoach() {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const role = user?.role ?? null

  return (
    <section id="ai-coach" className="py-20">
      <div className="mx-auto grid max-w-7xl items-center gap-12 px-4 sm:px-6 lg:grid-cols-2 lg:gap-16 lg:px-8">
        <div>
          <SectionHeading
            eyebrow="AI Study Coach"
            title="Your performance, explained — and turned into action."
            description="The AI Study Coach reads the performance data you actually have — your exam results, analytics, and practice history — and turns it into a direction for what to do next."
          />
          <div className="mt-6 space-y-3">
            {[
              'See your strengths and weaknesses summarized from real results',
              'Get AI-generated study priorities and recommendations',
              'Follow a Study Roadmap that unfolds from easy to medium to hard',
            ].map((item) => (
              <p key={item} className="flex items-start gap-3 text-sm leading-6 text-muted-foreground">
                <Check size={16} className="mt-0.5 shrink-0 text-primary" />
                {item}
              </p>
            ))}
          </div>
          <button
            type="button"
            onClick={() => navigate(user ? `/${role}` : '/register')}
            className="mt-8 inline-flex items-center gap-2 rounded-xl bg-primary px-6 py-3.5 text-sm font-semibold text-primary-foreground transition-opacity hover:opacity-90"
          >
            {user ? 'Open your AI Coach' : 'Get Started'}
            <ArrowRight size={16} />
          </button>
        </div>
        <Reveal>
          <AICoachVisual />
        </Reveal>
      </div>
    </section>
  )
}

function Practice() {
  return (
    <section id="practice" className="bg-muted/40 py-20">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <SectionHeading
          center
          eyebrow="Practice"
          title="When you know what to practice, practice works."
          description="Practice fits the difficulty you need, so you are never lost in random questions or re-reviewing things you already know."
        />
        <div className="mt-12 grid gap-4 md:grid-cols-3">
          {practiceLevels.map((level, index) => (
            <Reveal key={level.label} delay={index * 0.06}>
              <div className="h-full rounded-2xl border border-border bg-card p-6">
                <span className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary">
                  <level.icon size={20} />
                </span>
                <h3 className="mt-4 text-lg font-semibold">{level.label}</h3>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{level.description}</p>
              </div>
            </Reveal>
          ))}
        </div>
        <Reveal delay={0.12}>
          <div className="mt-6 rounded-2xl border border-border bg-card p-6 text-sm leading-6 text-muted-foreground">
            <p className="flex items-start gap-3">
              <Sparkles size={16} className="mt-0.5 shrink-0 text-primary" />
              <span>
                During a practice session you get instant feedback on each answer and a result summary when you finish.
                After practice, AI can help explain why an answer was correct or incorrect and what to work on next.
              </span>
            </p>
          </div>
        </Reveal>
      </div>
    </section>
  )
}

function Trust({ items }: { items: { icon: LucideIcon; title: string; description: string }[] }) {
  return (
    <section id="trust" className="py-20">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <SectionHeading
          center
          eyebrow="Security & trust"
          title="An assessment platform you can rely on."
          description="Security matters most where you cannot see it. Here is how Examora keeps accounts, exams, and results protected."
        />
        <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {items.map((item, index) => (
            <Reveal key={item.title} delay={index * 0.05}>
              <div className="h-full rounded-2xl border border-border bg-card p-6">
                <span className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary">
                  <item.icon size={20} />
                </span>
                <h3 className="mt-4 text-base font-semibold">{item.title}</h3>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{item.description}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  )
}

function Educators({ points }: { points: { icon: LucideIcon; title: string; description: string }[] }) {
  return (
    <section id="for-educators" className="bg-muted/40 py-20">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="grid gap-10 lg:grid-cols-[1fr_1.2fr] lg:items-center lg:gap-16">
          <SectionHeading
            eyebrow="For educators"
            title="Run better assessments for your students."
            description="Examora gives you the tools to build, assign, and monitor exams — and to see how your students are really doing."
          />
          <div className="grid gap-4 sm:grid-cols-2">
            {points.map((point, index) => (
              <Reveal key={point.title} delay={index * 0.05}>
                <div className="h-full rounded-2xl border border-border bg-card p-5">
                  <span className="grid size-10 place-items-center rounded-xl bg-primary/10 text-primary">
                    <point.icon size={18} />
                  </span>
                  <h3 className="mt-3 text-base font-semibold">{point.title}</h3>
                  <p className="mt-1.5 text-sm leading-6 text-muted-foreground">{point.description}</p>
                </div>
              </Reveal>
            ))}
          </div>
        </div>
        <p className="mt-8 flex items-center gap-2 text-sm text-muted-foreground">
          <GraduationCap size={16} className="text-primary" />
          Educator tools are available through the workspace.
        </p>
      </div>
    </section>
  )
}

function FinalCallToAction() {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const role = user?.role ?? null

  return (
    <section className="py-20">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <Reveal>
          <div className="relative overflow-hidden rounded-3xl bg-primary px-6 py-16 text-center text-primary-foreground sm:px-12">
            <div
              aria-hidden="true"
              className="pointer-events-none absolute inset-0"
              style={{ backgroundImage: 'radial-gradient(ellipse 70% 90% at 50% -20%, rgba(255,255,255,0.12), transparent)' }}
            />
            <div className="relative">
              <h2 className="mx-auto max-w-2xl text-3xl font-semibold tracking-tight sm:text-4xl">
                Make your next exam more useful.
              </h2>
              <p className="mx-auto mt-4 max-w-xl text-base leading-7 text-primary-foreground/80">
                Book any exam, understand what to improve, follow your roadmap, and practice where it counts. Your next
                learning step starts here.
              </p>
              <div className="mt-8 flex flex-wrap items-center justify-center gap-3.5">
                <button
                  type="button"
                  onClick={() => navigate(user ? `/${role}` : '/register')}
                  className="inline-flex items-center gap-2 rounded-xl bg-background px-6 py-3.5 text-sm font-semibold text-foreground transition-opacity hover:opacity-90"
                >
                  Get Started
                  <ArrowRight size={16} />
                </button>
                <a
                  href="#how-it-works"
                  className="inline-flex items-center gap-2 rounded-xl border border-primary-foreground/30 px-6 py-3.5 text-sm font-semibold transition-colors hover:bg-primary-foreground/10"
                >
                  Explore How It Works
                </a>
              </div>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  )
}

function Footer() {
  const year = new Date().getFullYear()
  return (
    <footer className="border-t border-border bg-background">
      <div className="mx-auto max-w-7xl px-4 py-14 sm:px-6 lg:px-8">
        <div className="grid gap-10 md:grid-cols-[1.4fr_1fr_1fr]">
          <div>
            <a href="#top" className="inline-flex items-center gap-2.5">
              <span className="grid size-8 place-items-center rounded-lg bg-primary text-sm font-bold text-primary-foreground">E</span>
              <span className="text-base font-semibold tracking-tight">Examora</span>
            </a>
            <p className="mt-4 max-w-sm text-sm leading-6 text-muted-foreground">
              Smart online exams and AI-powered learning guidance for students and educators.
            </p>
          </div>
          <div>
            <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">Product</p>
            <ul className="mt-4 space-y-2.5">
              {navLinks.map((link) => (
                <li key={link.href}>
                  <a href={link.href} className="text-sm text-muted-foreground transition-colors hover:text-foreground">
                    {link.label}
                  </a>
                </li>
              ))}
            </ul>
          </div>
          <div>
            <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">Account</p>
            <ul className="mt-4 space-y-2.5">
              <li>
                <a href="/login" className="text-sm text-muted-foreground transition-colors hover:text-foreground">
                  Login
                </a>
              </li>
              <li>
                <a href="/register" className="text-sm text-muted-foreground transition-colors hover:text-foreground">
                  Register
                </a>
              </li>
            </ul>
          </div>
        </div>
        <div className="mt-12 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-6">
          <p className="text-xs text-muted-foreground">© {year} Examora. All rights reserved.</p>
          <p className="text-xs text-muted-foreground">Made with a focus on honest learning.</p>
        </div>
      </div>
    </footer>
  )
}

export function LandingPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const user = useAuthStore((s) => s.user)
  const role = user?.role ?? null

  const legacyToken = searchParams.get('token')
  if (legacyToken) {
    return <OAuthCallback />
  }

  return (
    <div className="min-h-screen bg-background text-foreground">
      <a href="#how-it-works" className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded-lg focus:bg-background focus:px-3 focus:py-2 focus:text-sm focus:font-medium">
        Skip to content
      </a>
      <Navbar />
      <main>
        <Hero />
        <Problem points={problemPoints} />
        <Solution steps={learningLoopSteps} />
        <HowItWorks steps={howItWorks} />
        <Features items={benefits} />
        <AICoach />
        <Practice />
        <Trust items={trustPoints} />
        <Educators points={teacherPoints} />
        <FinalCallToAction />
      </main>
      <Footer />
    </div>
  )
}