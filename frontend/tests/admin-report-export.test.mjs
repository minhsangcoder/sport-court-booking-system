import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import vm from 'node:vm'
import { crc32 } from 'node:zlib'
import ts from 'typescript'

const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)))
function load(file) {
  const source = readFileSync(path.join(root, file), 'utf8')
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  })
  const cjs = { exports: {} }
  vm.runInNewContext(outputText, {
    module: cjs,
    exports: cjs.exports,
    TextEncoder,
    Blob,
    URLSearchParams,
    Date,
  })
  return cjs.exports
}
const range = load('features/admin/report-range.ts')
const sheet = load('lib/spreadsheet.ts')

test('quick report periods are Vietnam calendar days ending today', () => {
  const today = '2026-11-06'
  assert.deepEqual({ ...range.presetRange('today', today) }, { from: today, to: today })
  assert.deepEqual({ ...range.presetRange('7d', today) }, { from: '2026-10-31', to: today })
  assert.deepEqual({ ...range.presetRange('30d', today) }, { from: '2026-10-08', to: today })
  assert.deepEqual({ ...range.presetRange('month', today) }, { from: '2026-11-01', to: today })
  assert.deepEqual({ ...range.presetRange('quarter', today) }, { from: '2026-10-01', to: today })
  assert.equal(range.presetRange('quarter', '2026-03-31').from, '2026-01-01')
  assert.equal(range.vietnamToday(new Date('2026-11-05T17:30:00Z')), '2026-11-06')
  assert.equal(
    range.rangeQuery({ from: today, to: today }),
    'from=2026-11-05T17%3A00%3A00.000Z&to=2026-11-06T17%3A00%3A00.000Z',
  )
})

test('previous period has the same length and ends where the current one starts', () => {
  const path = range.previousPath({ from: '2026-11-01T00:00:00Z', to: '2026-11-08T00:00:00Z' })
  assert.equal(
    decodeURIComponent(path),
    '/admin/bookings/statistics?from=2026-10-25T00:00:00.000Z&to=2026-11-01T00:00:00.000Z',
  )
  assert.deepEqual(
    { ...range.periodLabel({ from: '2026-11-05T17:00:00Z', to: '2026-11-06T17:00:00Z' }) },
    { from: '06/11/2026', to: '06/11/2026' },
  )
})

test('xlsx export is a valid store-only zip with one worksheet per table', async () => {
  const blob = sheet.xlsxBlob([
    {
      name: 'Tổng quan',
      rows: [
        ['Chỉ số', 'Kỳ này'],
        ['Lượt đặt <mới> & "đủ"', 50],
      ],
    },
    {
      name: 'Cơ sở theo tỉnh/thành',
      rows: [
        ['Tỉnh', 'Số'],
        ['Hà Nội', 3],
      ],
    },
  ])
  const bytes = new Uint8Array(await blob.arrayBuffer())
  const view = new DataView(bytes.buffer)
  const entries = new Map()
  for (let at = 0; view.getUint32(at, true) === 0x04034b50; ) {
    const size = view.getUint32(at + 18, true)
    const nameLength = view.getUint16(at + 26, true)
    const name = new TextDecoder().decode(bytes.subarray(at + 30, at + 30 + nameLength))
    const data = bytes.subarray(at + 30 + nameLength, at + 30 + nameLength + size)
    assert.equal(view.getUint32(at + 14, true), crc32(data), `crc of ${name}`)
    entries.set(name, new TextDecoder().decode(data))
    at += 30 + nameLength + size
  }
  assert.deepEqual(
    [...entries.keys()],
    [
      '[Content_Types].xml',
      '_rels/.rels',
      'xl/workbook.xml',
      'xl/_rels/workbook.xml.rels',
      'xl/styles.xml',
      'xl/worksheets/sheet1.xml',
      'xl/worksheets/sheet2.xml',
    ],
  )
  assert.match(entries.get('xl/workbook.xml'), /name="Cơ sở theo tỉnh thành"/)
  assert.match(entries.get('xl/worksheets/sheet1.xml'), /Lượt đặt &lt;mới&gt; &amp; &quot;đủ&quot;/)
  assert.match(entries.get('xl/worksheets/sheet1.xml'), /<c r="B2"><v>50<\/v><\/c>/)
})

test('csv export keeps Vietnamese text for Excel and quotes only when needed', async () => {
  const rows = [
    ['Chỉ số', 'Kỳ này'],
    ['Có, "hủy"', 4],
  ]
  const bytes = Buffer.from(await sheet.csvBlob([{ name: 'Tổng quan', rows }]).arrayBuffer())
  assert.deepEqual([...bytes.subarray(0, 3)], [0xef, 0xbb, 0xbf])
  assert.equal(bytes.subarray(3).toString('utf8'), 'Tổng quan\r\nChỉ số,Kỳ này\r\n"Có, ""hủy""",4')
})
