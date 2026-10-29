import test from 'node:test'
import assert from 'node:assert/strict'
import * as jsx from 'react/jsx-runtime'
import { moduleLoader, hookDriver, flush } from './support/load-module.mjs'

function nodes(tree) {
  if (!tree || typeof tree !== 'object') return []
  if (Array.isArray(tree)) return tree.flatMap(nodes)
  return [tree, ...nodes(tree.props?.children)]
}
test('cash requires explicit received-money confirmation and suppresses duplicate clicks', async () => {
  const driver=hookDriver(), calls=[], received=[]
  let finish
  const load=moduleLoader({}, {
    react:driver.react,'react/jsx-runtime':jsx,'next/link':{default:'a'},
    '@/components/ui/button':{Button:'button'},
    '@/lib/api':{api:(path,options)=>{calls.push({path,...options});return new Promise(resolve=>{finish=resolve})}},
    '@/lib/use-api':{useApi:()=>({})},'@/lib/use-polling':{usePolling:()=>{}},
    '@/features/schedule/types':{money:n=>`${n}đ`},
    '@/features/booking/payment-synchronization':{PaymentSynchronization:()=>null},
  })
  const Form=load('features/payments/counter-cash-payment.tsx').CounterCashPayment
  const render=()=>driver.render(()=>Form({payment:{id:'payment',amount:120000,currency:'VND'},onReceived:id=>received.push(id)}))
  nodes(render()).find(n=>n.type==='button').props.onClick()
  assert.equal(calls.length,0)
  const submit=nodes(render()).find(n=>n.type==='button'&&n.props.children==='Xác nhận đã nhận đủ tiền mặt')
  const pending=submit.props.onClick();submit.props.onClick()
  assert.equal(calls.length,1)
  assert.equal(calls[0].path,'/payments/payment/counter-cash')
  assert.equal(calls[0].method,'POST')
  finish({id:'cash-receipt'});await pending;await flush()
  assert.deepEqual(received,['cash-receipt'])
  driver.unmount()
})
