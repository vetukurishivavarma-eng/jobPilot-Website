import { createContext, useContext } from 'react'
import type { User } from './api'

type Session = { user: User | null; setUser: (u: User | null) => void }
export const SessionCtx = createContext<Session>({ user: null, setUser: () => {} })
export const useSession = () => useContext(SessionCtx)
