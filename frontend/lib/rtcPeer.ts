export type PeerRole = 'initiator' | 'responder'
export type PeerConnectionState = 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed'

export type SignalMessage = {
  type: 'offer' | 'answer' | 'ice'
  sdp?: RTCSessionDescriptionInit | null
  candidate?: RTCIceCandidateInit | null
}

export type RtcPeerOptions = {
  role: PeerRole
  iceServers?: RTCIceServer[]
  onSignal: (signal: SignalMessage) => void
  onStateChange?: (state: PeerConnectionState) => void
  onRemoteStream?: (stream: MediaStream) => void
}

const DEFAULT_ICE_SERVERS: RTCIceServer[] = [{ urls: 'stun:stun.l.google.com:19302' }]

export class RtcPeer {
  private readonly role: PeerRole
  private readonly onSignal: (signal: SignalMessage) => void
  private readonly onStateChange?: (state: PeerConnectionState) => void
  private readonly onRemoteStream?: (stream: MediaStream) => void
  private pc: RTCPeerConnection | null
  private readonly remoteStream = new MediaStream()
  private readonly attachedTrackIds = new Set<string>()
  private pendingCandidates: RTCIceCandidateInit[] = []

  constructor(options: RtcPeerOptions) {
    this.role = options.role
    this.onSignal = options.onSignal
    this.onStateChange = options.onStateChange
    this.onRemoteStream = options.onRemoteStream
    this.pc = new RTCPeerConnection({
      iceServers: options.iceServers && options.iceServers.length > 0 ? options.iceServers : DEFAULT_ICE_SERVERS,
    })
    this.pc.onicecandidate = (event) => {
      if (event.candidate && this.pc) this.onSignal({ type: 'ice', candidate: event.candidate.toJSON() })
    }
    this.pc.ontrack = (event) => {
      if (event.track && this.pc) this.remoteStream.addTrack(event.track)
      if (this.remoteStream.getTracks().length > 0) this.onRemoteStream?.(this.remoteStream)
    }
    this.pc.onconnectionstatechange = () => {
      if (this.pc) this.onStateChange?.(this.pc.connectionState)
    }
  }

  get remote(): MediaStream {
    return this.remoteStream
  }

  get state(): PeerConnectionState {
    return this.pc ? this.pc.connectionState : 'closed'
  }

  get roleType(): PeerRole {
    return this.role
  }

  async start(stream: MediaStream): Promise<void> {
    if (!this.pc) return
    for (const track of stream.getTracks()) {
      if (this.attachedTrackIds.has(track.id)) continue
      this.attachedTrackIds.add(track.id)
      this.pc.addTrack(track, stream)
    }
    const offer = await this.pc.createOffer()
    if (!this.pc) return
    await this.pc.setLocalDescription(offer)
    if (this.pc?.localDescription) {
      this.onSignal({ type: 'offer', sdp: this.pc.localDescription.toJSON() })
    }
  }

  async handleSignal(signal: SignalMessage): Promise<void> {
    if (!this.pc) return
    if (signal.type === 'offer' && signal.sdp) await this.acceptOffer(signal.sdp)
    else if (signal.type === 'answer' && signal.sdp) await this.acceptAnswer(signal.sdp)
    else if (signal.type === 'ice' && signal.candidate) await this.addIceCandidate(signal.candidate)
  }

  private async acceptOffer(sdp: RTCSessionDescriptionInit): Promise<void> {
    if (!this.pc) return
    await this.pc.setRemoteDescription(new RTCSessionDescription(sdp))
    if (!this.pc) return
    await this.flushQueuedCandidates()
    if (!this.pc) return
    const answer = await this.pc.createAnswer()
    if (!this.pc) return
    await this.pc.setLocalDescription(answer)
    if (this.pc?.localDescription) {
      this.onSignal({ type: 'answer', sdp: this.pc.localDescription.toJSON() })
    }
  }

  private async acceptAnswer(sdp: RTCSessionDescriptionInit): Promise<void> {
    if (!this.pc) return
    await this.pc.setRemoteDescription(new RTCSessionDescription(sdp))
    if (!this.pc) return
    await this.flushQueuedCandidates()
  }

  private async addIceCandidate(candidate: RTCIceCandidateInit): Promise<void> {
    if (!this.pc) return
    if (!this.pc.remoteDescription) {
      this.pendingCandidates.push(candidate)
      return
    }
    await this.pc.addIceCandidate(new RTCIceCandidate(candidate))
  }

  private async flushQueuedCandidates(): Promise<void> {
    if (!this.pc) return
    const queued = this.pendingCandidates
    this.pendingCandidates = []
    for (const candidate of queued) {
      if (!this.pc) return
      await this.pc.addIceCandidate(new RTCIceCandidate(candidate))
    }
  }

  stop(): void {
    if (!this.pc) return
    this.pc.onicecandidate = null
    this.pc.ontrack = null
    this.pc.onconnectionstatechange = null
    this.pc.close()
    this.pc = null
    this.attachedTrackIds.clear()
    this.pendingCandidates = []
    this.remoteStream.getTracks().forEach((track) => track.stop())
  }
}