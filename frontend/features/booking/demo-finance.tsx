'use client'

export function notifyDemoMoneyChanged() {
  window.dispatchEvent(new Event('sporthub:demo-money-changed'))
  try {
    localStorage.setItem('sporthub:demo-money-changed', `${Date.now()}:${crypto.randomUUID()}`)
  } catch {
    /* A blocked browser store does not affect the committed server operation. */
  }
}
