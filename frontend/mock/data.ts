import type { AnalyticsPoint, Exam, Result, User } from '@/types'
export const mockUsers: User[] = [{ id: 'u1', name: 'Alex Morgan', email: 'alex@examwise.edu', role: 'STUDENT' }, { id: 'u2', name: 'Dr. Maya Patel', email: 'maya@examwise.edu', role: 'TEACHER' }, { id: 'u3', name: 'Jordan Lee', email: 'jordan@examwise.edu', role: 'ADMIN' }]
export const mockExams: Exam[] = [
  { id: 'e1', title: 'Advanced Mathematics', subject: 'Mathematics', date: '2026-09-04', duration: 90, status: 'UPCOMING', participants: 42 },
  { id: 'e2', title: 'Modern World History', subject: 'History', date: '2026-09-08', duration: 60, status: 'UPCOMING', participants: 36 },
  { id: 'e3', title: 'Introduction to Biology', subject: 'Biology', date: '2026-08-12', duration: 75, status: 'COMPLETED', participants: 58, averageScore: 82 },
  { id: 'e4', title: 'Computer Science Fundamentals', subject: 'Computer Science', date: '2026-08-20', duration: 90, status: 'COMPLETED', participants: 64, averageScore: 76 },
  { id: 'e5', title: 'Physics: Mechanics', subject: 'Physics', date: '2026-09-15', duration: 90, status: 'DRAFT', participants: 0 },
]
export const mockResults: Result[] = [{ id: 'r1', examTitle: 'Introduction to Biology', subject: 'Biology', score: 88, total: 100, date: '2026-08-12' }, { id: 'r2', examTitle: 'Computer Science Fundamentals', subject: 'Computer Science', score: 76, total: 100, date: '2026-08-20' }, { id: 'r3', examTitle: 'English Literature', subject: 'English', score: 94, total: 100, date: '2026-07-28' }]
export const mockAnalytics: AnalyticsPoint[] = [{ month: 'Mar', score: 68, exams: 12 }, { month: 'Apr', score: 74, exams: 18 }, { month: 'May', score: 72, exams: 24 }, { month: 'Jun', score: 81, exams: 31 }, { month: 'Jul', score: 78, exams: 38 }, { month: 'Aug', score: 86, exams: 46 }]
