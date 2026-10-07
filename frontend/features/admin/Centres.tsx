'use client'
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Building2, DoorOpen, MapPin, Phone, Mail, Plus, Save, Trash2, X, UserRound } from 'lucide-react'
import { centreApi, centreRoomApi, type CentrePayload, type CentreRoomPayload } from '@/services/centreApi'
import { userApi } from '@/services/userApi'
import type { Centre, CentreRoom } from '@/types/enrolment'
import { useToast } from '@/components/feedback'

function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return <label className="block text-sm font-medium">{label}{children}{error && <span className="mt-1 block text-xs text-destructive">{error}</span>}</label>
}

const EMPTY_ROOM: CentreRoomPayload = { centreId: '', roomName: '', roomCode: '', capacity: 30, invigilatorId: null }

export default function Centres() {
  const toast = useToast()
  const queryClient = useQueryClient()
  const centresQuery = useQuery({ queryKey: ['admin-centres'], queryFn: async () => (await centreApi.list()).data.data, retry: 1 })
  const teachersQuery = useQuery({ queryKey: ['all-teachers'], queryFn: async () => (await userApi.list()).data.data, retry: 1 })
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<Centre | null>(null)
  const [form, setForm] = useState<CentrePayload>({ name: '', code: '', address: '', contactPhone: '', contactEmail: '' })
  const [openCentre, setOpenCentre] = useState('')
  const [roomForm, setRoomForm] = useState<CentreRoomPayload>(EMPTY_ROOM)
  const [roomEditing, setRoomEditing] = useState<CentreRoom | null>(null)

  const roomsQuery = useQuery({
    queryKey: ['centre-rooms', openCentre],
    queryFn: async () => (await centreApi.rooms(openCentre)).data.data,
    enabled: Boolean(openCentre),
    retry: 1,
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['admin-centres'] })
    if (openCentre) queryClient.invalidateQueries({ queryKey: ['centre-rooms', openCentre] })
  }

  const saveCentre = useMutation({
    mutationFn: () => (editing ? centreApi.update(editing.id, form) : centreApi.create(form)),
    onSuccess: () => { toast.success(editing ? 'Centre updated.' : 'Centre created.'); setCreating(false); setEditing(null); invalidate() },
    onError: (cause: any) => toast.error(cause?.response?.data?.message || 'Unable to save this centre.'),
  })

  const deleteCentre = useMutation({
    mutationFn: (id: string) => centreApi.remove(id),
    onSuccess: () => { toast.success('Centre removed.'); setOpenCentre(''); invalidate() },
    onError: (cause: any) => toast.error(cause?.response?.data?.message || 'Unable to remove this centre.'),
  })

  const saveRoom = useMutation({
    mutationFn: () => (roomEditing ? centreRoomApi.update(roomEditing.id, roomForm) : centreRoomApi.create({ ...roomForm, centreId: openCentre })),
    onSuccess: () => { toast.success(roomEditing ? 'Room updated.' : 'Room added.'); setRoomEditing(null); setRoomForm(EMPTY_ROOM); invalidate() },
    onError: (cause: any) => toast.error(cause?.response?.data?.message || 'Unable to save this room.'),
  })

  const deleteRoom = useMutation({
    mutationFn: (id: string) => centreRoomApi.remove(id),
    onSuccess: () => { toast.success('Room removed.'); invalidate() },
    onError: (cause: any) => toast.error(cause?.response?.data?.message || 'Unable to remove this room.'),
  })

  const openForm = (centre?: Centre) => {
    setEditing(centre ?? null)
    setCreating(true)
    setForm(centre ? { name: centre.name, code: centre.code, address: centre.address ?? '', contactPhone: centre.contactPhone ?? '', contactEmail: centre.contactEmail ?? '' } : { name: '', code: '', address: '', contactPhone: '', contactEmail: '' })
  }

  const editRoom = (room: CentreRoom) => {
    setRoomEditing(room)
    setRoomForm({ centreId: room.centreId, roomName: room.roomName, roomCode: room.roomCode, capacity: room.capacity, invigilatorId: room.invigilatorId ?? null })
  }

  const teacherOptions = teachersQuery.data ?? []

  const teacherName = (id?: string | null) => teacherOptions.find(t => t.id === id)?.name

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div><p className="text-xs font-semibold uppercase tracking-widest text-primary">Administration</p><h1 className="mt-2 text-3xl font-semibold tracking-tight">Exam centres</h1><p className="mt-2 text-sm leading-6 text-muted-foreground">Manage exam centres and their physical rooms. Students are auto-seated at enrolment.</p></div>
        <button type="button" onClick={() => openForm()} className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground"><Plus size={16} />New centre</button>
      </header>

      {creating && (
        <div className="rounded-2xl border border-border bg-card p-5">
          <div className="mb-4 flex items-center justify-between"><h2 className="font-semibold">{editing ? 'Edit centre' : 'New centre'}</h2><button type="button" className="text-muted-foreground" onClick={() => { setCreating(false); setEditing(null) }}><X size={18} /></button></div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Name"><input className="field mt-2" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></Field>
            <Field label="Code"><input className="field mt-2" value={form.code} onChange={e => setForm({ ...form, code: e.target.value })} placeholder="CEN-001" /></Field>
            <Field label="Address"><input className="field mt-2" value={form.address ?? ''} onChange={e => setForm({ ...form, address: e.target.value })} /></Field>
            <Field label="Contact phone"><input className="field mt-2" value={form.contactPhone ?? ''} onChange={e => setForm({ ...form, contactPhone: e.target.value })} /></Field>
            <Field label="Contact email"><input className="field mt-2" value={form.contactEmail ?? ''} onChange={e => setForm({ ...form, contactEmail: e.target.value })} /></Field>
          </div>
          <div className="mt-5 flex gap-2">
            <button type="button" disabled={saveCentre.isPending || form.name.trim().length < 2 || form.code.trim().length < 2} onClick={() => saveCentre.mutate()} className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"><Save size={15} />{saveCentre.isPending ? 'Saving...' : 'Save centre'}</button>
            <button type="button" className="rounded-xl border border-border px-4 py-2 text-sm" onClick={() => { setCreating(false); setEditing(null) }}>Cancel</button>
          </div>
        </div>
      )}

      <div className="space-y-3">
        {centresQuery.isPending ? <div className="h-40 animate-pulse rounded-2xl bg-muted" aria-busy="true" /> : centresQuery.isError ? <p className="text-sm text-destructive">Unable to load centres.</p> : centresQuery.data?.length ? centresQuery.data.map(centre => (
          <div key={centre.id} className="rounded-2xl border border-border bg-card">
            <div className="flex flex-wrap items-center justify-between gap-3 p-5">
              <div className="flex items-start gap-3">
                <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-primary/10 text-primary"><Building2 size={18} /></div>
                <div><h2 className="font-semibold">{centre.name}</h2><p className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-sm text-muted-foreground"><span className="inline-flex items-center gap-1"><MapPin size={13} />{centre.address || 'No address'}</span>{centre.contactPhone && <span className="inline-flex items-center gap-1"><Phone size={13} />{centre.contactPhone}</span>}{centre.contactEmail && <span className="inline-flex items-center gap-1"><Mail size={13} />{centre.contactEmail}</span>}</p><p className="mt-1 text-xs text-muted-foreground">{centre.code} · {centre.roomCount ?? 0} rooms · {centre.status}</p>
                  <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
                    <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-0.5 font-medium text-muted-foreground">Capacity {centre.totalCapacity ?? 0}</span>
                    <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-0.5 font-medium text-primary">Assigned {centre.assignedSeats ?? 0}</span>
                    <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 font-medium ${(centre.availableSeats ?? 0) < 0 ? 'bg-destructive/10 text-destructive' : 'bg-emerald-500/10 text-emerald-600'}`}>{(centre.availableSeats ?? 0) < 0 ? 'Over capacity' : 'Available'} {Math.abs(centre.availableSeats ?? 0)}</span>
                  </div>
                </div>
              </div>
              <div className="flex gap-2">
                <button type="button" className="rounded-lg border border-border px-3 py-2 text-xs" onClick={() => setOpenCentre(openCentre === centre.id ? '' : centre.id)}><DoorOpen size={14} className="mr-1 inline" />Rooms ({centre.roomCount ?? 0})</button>
                <button type="button" className="rounded-lg border border-border px-3 py-2 text-xs" onClick={() => openForm(centre)}>Edit</button>
                <button type="button" className="rounded-lg p-2 text-muted-foreground" onClick={() => deleteCentre.mutate(centre.id)}><Trash2 size={15} /></button>
              </div>
            </div>
            {openCentre === centre.id && (
              <div className="border-t border-border p-5">
                <div className="mb-4 grid gap-4 rounded-xl bg-muted/50 p-4 sm:grid-cols-2 lg:grid-cols-4">
                  {!roomEditing && <Field label="Room name"><input className="field mt-2" value={roomForm.roomName} onChange={e => setRoomForm({ ...roomForm, roomName: e.target.value })} /></Field>}
                  {!roomEditing && <Field label="Room code"><input className="field mt-2" value={roomForm.roomCode} onChange={e => setRoomForm({ ...roomForm, roomCode: e.target.value })} placeholder="R-101" /></Field>}
                  <Field label="Capacity"><input className="field mt-2" type="number" min={1} value={roomForm.capacity} onChange={e => setRoomForm({ ...roomForm, capacity: Number(e.target.value) || 1 })} /></Field>
                  <Field label="Invigilator (teacher)">
                    <select className="field mt-2" value={roomForm.invigilatorId ?? ''} onChange={e => setRoomForm({ ...roomForm, invigilatorId: e.target.value || null })}>
                      <option value="">None</option>
                      {teacherOptions.filter(t => t.role === 'TEACHER').map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                    </select>
                  </Field>
                  {roomEditing && <Field label="Room name"><input className="field mt-2" value={roomForm.roomName} onChange={e => setRoomForm({ ...roomForm, roomName: e.target.value })} /></Field>}
                  {roomEditing && <Field label="Room code"><input className="field mt-2" value={roomForm.roomCode} onChange={e => setRoomForm({ ...roomForm, roomCode: e.target.value })} /></Field>}
                  <div className="flex items-end gap-2">
                    <button type="button" disabled={saveRoom.isPending || roomForm.roomName.trim().length < 2 || roomForm.roomCode.trim().length < 2} onClick={() => saveRoom.mutate()} className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"><Save size={15} />{roomEditing ? 'Save changes' : 'Add room'}</button>
                    {roomEditing && <button type="button" className="rounded-xl border border-border px-3 py-2 text-sm" onClick={() => setRoomEditing(null)}>Cancel</button>}
                  </div>
                </div>
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {roomsQuery.isPending ? <p className="text-sm text-muted-foreground">Loading rooms...</p> : roomsQuery.data?.length ? roomsQuery.data.map(room => (
                    <div key={room.id} className="rounded-xl border border-border p-4">
                      <div className="flex items-center justify-between gap-2">
                        <div className="min-w-0"><p className="truncate font-medium">{room.roomName}</p><p className="text-xs text-muted-foreground">{room.roomCode} · {room.capacity} seats · {room.occupied ?? 0} occupied · {room.availableSeats ?? 0} free</p></div>
                        <div className="flex shrink-0 gap-1">
                          <button type="button" className="rounded-lg p-2 text-muted-foreground" onClick={() => editRoom(room)}>Edit</button>
                          <button type="button" className="rounded-lg p-2 text-muted-foreground" onClick={() => deleteRoom.mutate(room.id)}><Trash2 size={15} /></button>
                        </div>
                      </div>
                      {room.invigilatorName && <p className="mt-2 inline-flex items-center gap-1 text-xs text-muted-foreground"><UserRound size={12} />{room.invigilatorName}</p>}
                    </div>
                  )) : <p className="text-sm text-muted-foreground">No rooms in this centre yet.</p>}
                </div>
              </div>
            )}
          </div>
        )) : <p className="rounded-2xl border border-border bg-card p-10 text-center text-sm text-muted-foreground">No exam centres yet. Create one to start assigning rooms.</p>}
      </div>
    </div>
  )
}