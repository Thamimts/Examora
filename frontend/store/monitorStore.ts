import { create } from 'zustand'
import type { ProctorEvent, WebcamStatus } from '@/types/ai'
interface MonitorState { events: ProctorEvent[]; webcam: WebcamStatus; addEvent: (event: Omit<ProctorEvent, 'timestamp'>) => void; setWebcam: (webcam: Partial<WebcamStatus>) => void; clearEvents: () => void }
export const useMonitorStore = create<MonitorState>((set) => ({ events: [], webcam: { supported: false, permission: 'unknown', active: false }, addEvent: (event) => set((state) => ({ events: [...state.events, { ...event, timestamp: new Date().toISOString() }] })), setWebcam: (webcam) => set((state) => ({ webcam: { ...state.webcam, ...webcam } })), clearEvents: () => set({ events: [] }) }))
