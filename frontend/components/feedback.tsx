'use client'

import { createContext, useContext, useState } from 'react'
import { AlertTriangle, CheckCircle2, X } from 'lucide-react'

type Toast = { id: number; message: string; tone: 'success' | 'error' }
const ToastContext = createContext<{ success: (message: string) => void; error: (message: string) => void }>({ success: () => undefined, error: () => undefined })

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const push = (message: string, tone: Toast['tone']) => {
    const id = Date.now()
    setToasts(items => [...items, { id, message, tone }])
    window.setTimeout(() => setToasts(items => items.filter(item => item.id !== id)), 4200)
  }
  return <ToastContext.Provider value={{ success: message => push(message, 'success'), error: message => push(message, 'error') }}>
    {children}
    <div className="fixed right-4 top-4 z-50 grid w-[min(24rem,calc(100vw-2rem))] gap-2" aria-live="polite">
      {toasts.map(toast => <div key={toast.id} className={`flex items-start gap-3 rounded-xl border p-3 text-sm shadow-xl ${toast.tone === 'success' ? 'border-emerald-500/30 bg-card' : 'border-destructive/40 bg-card'}`}>
        {toast.tone === 'success' ? <CheckCircle2 className="mt-0.5 shrink-0 text-emerald-500" size={17} /> : <AlertTriangle className="mt-0.5 shrink-0 text-destructive" size={17} />}
        <p className="flex-1">{toast.message}</p><button type="button" className="rounded-md p-1 text-muted-foreground transition-opacity hover:opacity-70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-95" onClick={() => setToasts(items => items.filter(item => item.id !== toast.id))} aria-label="Dismiss notification"><X size={16}/></button>
      </div>)}
    </div>
  </ToastContext.Provider>
}

export const useToast = () => useContext(ToastContext)

export function ConfirmDialog({ open, title, description, confirmLabel = 'Confirm', busy, destructive = false, onCancel, onConfirm }: { open: boolean; title: string; description: string; confirmLabel?: string; busy?: boolean; destructive?: boolean; onCancel: () => void; onConfirm: () => void }) {
  if (!open) return null
  return <div className="fixed inset-0 z-40 grid place-items-center bg-background/75 p-4 backdrop-blur-sm" role="dialog" aria-modal="true" aria-labelledby="dialog-title">
    <section className="w-full max-w-md rounded-2xl border border-border bg-card p-6 shadow-2xl">
      <h2 id="dialog-title" className="text-lg font-semibold">{title}</h2><p className="mt-2 text-sm leading-6 text-muted-foreground">{description}</p>
      <div className="mt-6 flex justify-end gap-3"><button type="button" disabled={busy} aria-disabled={busy} onClick={onCancel} className="rounded-xl border border-border px-4 py-2 text-sm transition-[opacity,transform,box-shadow] duration-150 hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-50">Cancel</button><button type="button" disabled={busy} aria-busy={busy} aria-disabled={busy} onClick={onConfirm} className={`rounded-xl px-4 py-2 text-sm font-medium transition-[opacity,transform,box-shadow] duration-150 hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-50 ${destructive ? 'bg-destructive text-white' : 'bg-primary text-primary-foreground'}`}>{busy ? 'Working…' : confirmLabel}</button></div>
    </section>
  </div>
}
