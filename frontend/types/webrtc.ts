export type RtcSignalType = 'offer' | 'answer' | 'ice' | 'bye' | 'viewer_ready' | 'unavailable'
export type RtcSenderRole = 'STUDENT' | 'TEACHER' | 'ADMIN'

export type RtcSignal = {
  roomId: string
  senderId: string
  senderRole: RtcSenderRole
  peerId: string
  type: RtcSignalType
  sdp?: RTCSessionDescriptionInit | null
  candidate?: RTCIceCandidateInit | null
}

export const rtcSignalLabels = (type: RtcSignalType) => type.toUpperCase()