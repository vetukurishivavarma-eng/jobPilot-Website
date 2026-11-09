import { View } from 'react-native'
import { Tabs } from 'expo-router'
import { useColors } from '../../src/ui'

export default function TabsLayout() {
  const c = useColors()
  return (
    <Tabs screenOptions={{
      headerShown: false,
      tabBarActiveTintColor: c.ink,
      tabBarInactiveTintColor: c.muted,
      tabBarStyle: { backgroundColor: c.card, borderTopColor: c.line },
      tabBarLabelStyle: { fontSize: 12, fontWeight: '700', letterSpacing: 0.3 },
      // a pill instead of an icon: wide and green on the open tab
      tabBarIcon: ({ focused }) => <View style={{ width: focused ? 22 : 6, height: 6, borderRadius: 3, backgroundColor: focused ? c.accent : c.line }} />,
    }}>
      <Tabs.Screen name="index" options={{ title: 'Inbox' }} />
      <Tabs.Screen name="jobs" options={{ title: 'Jobs' }} />
      <Tabs.Screen name="applications" options={{ title: 'Applications' }} />
      <Tabs.Screen name="profile" options={{ title: 'Profile' }} />
    </Tabs>
  )
}
