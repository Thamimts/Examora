export type ExamRoomStatus = 'WAITING' | 'ACTIVE' | 'ENDED'
export type ExamRoomMemberStatus = 'JOINED' | 'LEFT'

export type ExamRoom = {
  roomId: string
  examId: string
  examTitle: string | null
  roomCode: string
  status: ExamRoomStatus
  createdBy: string
  startedAt: string | null
  endedAt: string | null
  createdAt: string | null
  memberCount: number
}

export type RoomMember = {
  memberId: string
  roomId: string
  studentId: string
  studentName: string | null
  studentEmail: string | null
  status: ExamRoomMemberStatus
  joinedAt: string | null
  leftAt: string | null
}

export type RoomDetail = {
  room: ExamRoom
  members: RoomMember[]
}

export type RoomActivity = {
  roomId: string
  examId: string
  roomCode: string
  status: ExamRoomStatus
  type: string
  message: string
  at: string
}

export type JoinRoomResponse = {
  roomId: string
  examId: string
  roomCode: string
  status: ExamRoomStatus
  message: string
}

export const roomStatusLabels: Record<ExamRoomStatus, string> = {
  WAITING: 'Waiting for students',
  ACTIVE: 'In progress',
  ENDED: 'Ended',
}