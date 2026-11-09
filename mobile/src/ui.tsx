import type { ReactNode } from 'react'
import { ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, TextInput, useColorScheme, View, type TextInputProps, type ViewStyle } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { router } from 'expo-router'

// Same palette as the website (ink / paper / signal green), plus a dark twin.
const light = {
  bg: '#f6f4ef', card: '#ffffff', ink: '#16181d', muted: '#6b6a64', line: '#dedad0',
  accent: '#1f6f5c', accentSoft: '#dcefe8', danger: '#b42318', warn: '#8a5300', warnSoft: '#fbeed5',
}
const dark: typeof light = {
  bg: '#111315', card: '#1a1d20', ink: '#ecebe6', muted: '#9a988f', line: '#2c3034',
  accent: '#5cc4a6', accentSoft: '#173a31', danger: '#f0857a', warn: '#e5b25a', warnSoft: '#3a2e17',
}
export type Colors = typeof light
export const useColors = () => (useColorScheme() === 'dark' ? dark : light)

export const space = 16

/** header: the screen sits under a stack header, which already covers the status bar. */
export function Screen({ children, onRefresh, refreshing = false, footer, header }: { children: ReactNode; onRefresh?: () => void; refreshing?: boolean; footer?: ReactNode; header?: boolean }) {
  const c = useColors()
  const top = useSafeAreaInsets().top
  return (
    <View style={{ flex: 1, backgroundColor: c.bg }}>
      <ScrollView
        contentContainerStyle={{ padding: space, paddingTop: header ? space : top + 12, paddingBottom: 48, gap: 14 }}
        keyboardShouldPersistTaps="handled"
        refreshControl={onRefresh ? <RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={c.accent} colors={[c.accent]} /> : undefined}>
        {children}
      </ScrollView>
      {footer}
    </View>
  )
}

export function Title({ children, kicker }: { children: ReactNode; kicker?: string }) {
  const c = useColors()
  return (
    <View style={{ marginBottom: 2 }}>
      {kicker && <Text style={[s.kicker, { color: c.accent }]}>{kicker}</Text>}
      <Text style={[s.title, { color: c.ink }]}>{children}</Text>
    </View>
  )
}

export function T({ children, muted, small, bold, style, selectable, onPress }: { children: ReactNode; muted?: boolean; small?: boolean; bold?: boolean; style?: object; selectable?: boolean; onPress?: () => void }) {
  const c = useColors()
  return <Text selectable={selectable} onPress={onPress} style={[{ color: muted ? c.muted : c.ink, fontSize: small ? 13 : 15, lineHeight: small ? 18 : 22, fontWeight: bold ? '700' : '400' }, style]}>{children}</Text>
}

export function Label({ children }: { children: ReactNode }) {
  const c = useColors()
  return <Text style={[s.label, { color: c.muted }]}>{children}</Text>
}

export function Card({ children, tone, style }: { children: ReactNode; tone?: 'accent' | 'warn' | 'danger'; style?: ViewStyle }) {
  const c = useColors()
  const border = tone === 'accent' ? c.accent : tone === 'warn' ? c.warn : tone === 'danger' ? c.danger : c.line
  return <View style={[s.card, { backgroundColor: tone === 'warn' ? c.warnSoft : tone === 'accent' ? c.accentSoft : c.card, borderColor: border }, style]}>{children}</View>
}

export function Btn({ title, onPress, kind = 'solid', disabled, busy, small }: { title: string; onPress: () => void; kind?: 'solid' | 'ghost' | 'danger' | 'accent'; disabled?: boolean; busy?: boolean; small?: boolean }) {
  const c = useColors()
  const bg = kind === 'solid' ? c.ink : kind === 'accent' ? c.accent : 'transparent'
  const fg = kind === 'solid' ? c.bg : kind === 'accent' ? '#ffffff' : kind === 'danger' ? c.danger : c.ink
  const off = disabled || busy
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ disabled: !!off }} disabled={off} onPress={onPress}
      style={({ pressed }) => [s.btn, small && s.btnSmall, { backgroundColor: bg, borderColor: kind === 'ghost' ? c.line : kind === 'danger' ? c.danger : bg, opacity: off ? 0.45 : pressed ? 0.75 : 1 }]}>
      {busy && <ActivityIndicator size="small" color={fg} />}
      <Text style={{ color: fg, fontWeight: '700', fontSize: small ? 13 : 15 }}>{title}</Text>
    </Pressable>
  )
}

export function Input({ label, error, hint, style, ...p }: TextInputProps & { label: string; error?: string; hint?: string }) {
  const c = useColors()
  return (
    <View style={{ gap: 6 }}>
      <Label>{label}</Label>
      <TextInput placeholderTextColor={c.muted} {...p}
        style={[s.input, { color: c.ink, backgroundColor: c.card, borderColor: error ? c.danger : c.line }, p.multiline && { minHeight: 110, textAlignVertical: 'top' }, style]} />
      {hint && !error && <T small muted>{hint}</T>}
      {error && <T small style={{ color: c.danger }}>{error}</T>}
    </View>
  )
}

/** Tappable pill: segmented controls, select options, filters. */
export function Chip({ label, on, onPress, disabled }: { label: string; on: boolean; onPress: () => void; disabled?: boolean }) {
  const c = useColors()
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ selected: on, disabled }} disabled={disabled} onPress={onPress}
      style={[s.chip, { backgroundColor: on ? c.ink : c.card, borderColor: on ? c.ink : c.line, opacity: disabled ? 0.5 : 1 }]}>
      <Text style={{ color: on ? c.bg : c.ink, fontWeight: '600', fontSize: 13 }}>{label}</Text>
    </Pressable>
  )
}

export function Tag({ label, tone }: { label: string; tone?: 'accent' | 'warn' | 'danger' | 'ink' }) {
  const c = useColors()
  const [bg, fg] = tone === 'accent' ? [c.accentSoft, c.accent] : tone === 'warn' ? [c.warnSoft, c.warn] : tone === 'danger' ? [c.warnSoft, c.danger] : tone === 'ink' ? [c.ink, c.bg] : [c.bg, c.muted]
  return <Text style={[s.tag, { backgroundColor: bg, color: fg }]}>{label}</Text>
}

export function Row({ children, gap = 8, wrap, style }: { children: ReactNode; gap?: number; wrap?: boolean; style?: ViewStyle }) {
  return <View style={[{ flexDirection: 'row', alignItems: 'center', gap, flexWrap: wrap ? 'wrap' : 'nowrap' }, style]}>{children}</View>
}

export function ErrorText({ children }: { children: ReactNode }) {
  const c = useColors()
  return children ? <Text accessibilityRole="alert" style={{ color: c.danger, fontSize: 14 }}>{children}</Text> : null
}

export function Score({ score, big }: { score: number; big?: boolean }) {
  const c = useColors()
  const tone = score >= 75 ? c.accent : score >= 50 ? c.warn : c.muted
  const size = big ? 64 : 46
  return (
    <View accessibilityLabel={`Match ${score}`} style={{ width: size, height: size, borderRadius: size / 2, borderWidth: 2, borderColor: tone, alignItems: 'center', justifyContent: 'center' }}>
      <Text style={{ color: tone, fontWeight: '800', fontSize: big ? 22 : 16, lineHeight: big ? 24 : 18 }}>{score}</Text>
      <Text style={{ color: tone, fontSize: 8, fontWeight: '700', letterSpacing: 0.8 }}>MATCH</Text>
    </View>
  )
}

export function Bar({ percent }: { percent: number }) {
  const c = useColors()
  return (
    <View style={{ height: 6, borderRadius: 3, backgroundColor: c.line, overflow: 'hidden' }}>
      <View style={{ width: `${percent}%`, height: 6, backgroundColor: c.accent }} />
    </View>
  )
}

export function Loading() {
  const c = useColors()
  return <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: c.bg }}><ActivityIndicator color={c.accent} /></View>
}

/** Sticky bar at the bottom of a screen (save / delete). */
export function Footer({ children }: { children: ReactNode }) {
  const c = useColors()
  const bottom = useSafeAreaInsets().bottom
  return <View style={[s.footer, { backgroundColor: c.card, borderTopColor: c.line, paddingBottom: 12 + bottom }]}>{children}</View>
}

const s = StyleSheet.create({
  kicker: { fontSize: 11, fontWeight: '800', letterSpacing: 1.6, textTransform: 'uppercase', marginBottom: 4 },
  title: { fontSize: 32, fontWeight: '800', letterSpacing: -0.8, lineHeight: 36 },
  label: { fontSize: 11, fontWeight: '700', letterSpacing: 1, textTransform: 'uppercase' },
  card: { borderWidth: 1, borderRadius: 14, padding: space, gap: 10 },
  btn: { flexDirection: 'row', gap: 8, alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderRadius: 10, paddingVertical: 13, paddingHorizontal: 18 },
  btnSmall: { paddingVertical: 8, paddingHorizontal: 12 },
  input: { borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 11, fontSize: 15 },
  chip: { borderWidth: 1, borderRadius: 999, paddingVertical: 8, paddingHorizontal: 14 },
  tag: { fontSize: 12, fontWeight: '700', paddingHorizontal: 8, paddingVertical: 3, borderRadius: 6, overflow: 'hidden' },
  footer: { flexDirection: 'row', gap: 10, alignItems: 'center', borderTopWidth: 1, paddingHorizontal: space, paddingTop: 12 },
})

/** Jobs and Applications open only with a 100% profile (same rule as the website). */
export function Locked({ gaps, what }: { gaps: { missing: string[]; percent: number } | null; what: string }) {
  if (!gaps) return <Loading />
  return (
    <Screen>
      <Title kicker={`Profile ${gaps.percent}%`}>Finish your profile first</Title>
      <T muted>{what} unlock at 100%. The agent can only answer application questions from what your profile says.</T>
      <Card tone="warn">
        <Bar percent={gaps.percent} />
        <Label>Still missing</Label>
        <Row wrap>{gaps.missing.map(m => <Tag key={m} label={m} tone="warn" />)}</Row>
      </Card>
      <Btn title="Open profile" onPress={() => router.navigate('/profile')} />
    </Screen>
  )
}
