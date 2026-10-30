export function mutationResources(path: string, method: string): string[] | null {
  const url = path.split('?')[0]
  if (
    method === 'POST' &&
    (/^\/transfers(?:\/[^/]+)?\/quote$/.test(url) ||
      ['/bookings/quote', '/schedules/public/quote'].includes(url))
  )
    return []
  if (/^\/transfers\/conversations\/[^/]+\/messages$/.test(url)) return [url]
  if (/^\/transfers\//.test(url) || url === '/transfers')
    return ['/transfers', '/bookings', '/payments/bank-profile']
  if (/^\/payments\/(?:bank-destinations|bookings\/[^/]+\/guest-bank-destinations)$/.test(url))
    return ['/payments/bank-profile', '/payments/bank-destinations', '/transfers', '/bookings']
  return null
}
