'use client'
import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { usePracticeStore } from './practiceStore'
import type { AuthResponse, AuthState, Role, User } from '@/types'
export const useAuthStore = create<AuthState>()(persist((set) => ({ user: null, token: null, hydrated: false, setAuth: ({ user, token }: AuthResponse) => { usePracticeStore.getState().clear(); set({ user, token }) }, logout: () => { usePracticeStore.getState().clear(); set({ user: null, token: null }) } }), { name: 'examwise-auth', partialize: (state) => ({ user: state.user, token: state.token }), onRehydrateStorage: () => () => useAuthStore.setState({ hydrated: true }) }))
export const getAuthSnapshot = () => useAuthStore.getState()
export const demoUser = (role: Role, name?: string): User => ({ id: `demo-${role.toLowerCase()}`, name: name || `${role[0]}${role.slice(1).toLowerCase()} Demo`, email: `${role.toLowerCase()}@examwise.edu`, role })
