export type Centre = {
  id: string
  name: string
  code: string
  address?: string | null
  contactPhone?: string | null
  contactEmail?: string | null
  status: 'ACTIVE' | 'INACTIVE'
  roomCount?: number
  totalCapacity?: number
  assignedSeats?: number
  availableSeats?: number
}

export type CentreRoom = {
  id: string
  centreId: string
  centreName?: string | null
  roomName: string
  roomCode: string
  capacity: number
  invigilatorId?: string | null
  invigilatorName?: string | null
  status: 'ACTIVE' | 'INACTIVE'
  occupied?: number
  availableSeats?: number
}

export type RoomSeatingStudent = {
  assignmentId: string
  studentId: string
  studentName: string
  studentEmail?: string | null
  rollNumber?: string | null
  seatNumber: number
  status?: string | null
  riskLevel?: string | null
}

export type RoomSeating = {
  roomId: string
  roomName: string
  roomCode: string
  capacity: number
  centreId: string
  centreName?: string | null
  invigilatorId?: string | null
  invigilatorName?: string | null
  status: 'ACTIVE' | 'INACTIVE'
  students: RoomSeatingStudent[]
  occupied: number
}

export type EnrollmentDetail = {
  enrollmentId: string
  examId: string
  examTitle: string
  subject?: string | null
  status: string
  enrolledAt?: string | null
  assignmentId?: string | null
  roomId?: string | null
  roomName?: string | null
  roomCode?: string | null
  centreName?: string | null
  centreId?: string | null
  seatNumber?: number | null
  attemptStatus?: string | null
}

export type ExamRosterEntry = {
  enrollmentId: string
  studentId: string
  studentName: string
  studentEmail?: string | null
  rollNumber?: string | null
  roomId?: string | null
  roomName?: string | null
  seatNumber?: number | null
  attemptStatus?: string | null
  riskLevel?: string | null
}

export type InvigilatorRoomSummary = {
  roomId: string
  roomName: string
  roomCode: string
  roomStatus: 'ACTIVE' | 'INACTIVE'
  centreId: string
  centreName?: string | null
  capacity: number
  occupied: number
  examId?: string | null
  examTitle?: string | null
  examStatus?: string | null
  activeCount: number
  submittedCount: number
  riskLevel?: string | null
  riskScore?: number | null
  live: boolean
}