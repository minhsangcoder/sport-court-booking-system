'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import type { useContact } from '@/features/profile/use-contact'
import { channels, labels } from '@/features/profile/use-contact-shared'
import { AlertCircle, CheckCircle2, Mail, Phone, RefreshCw, Send } from 'lucide-react'
export function ContactChannels({
  model,
}: {
  model: Pick<
    ReturnType<typeof useContact>,
    'loading' | 'challenges' | 'user' | 'now' | 'requestChange' | 'busy' | 'verify' | 'resend'
  >
}) {
  const { loading, challenges, user, now, requestChange, busy, verify, resend } = model
  return (
    <div className="mt-6 grid grid-cols-1 gap-6 md:grid-cols-2">
      {!loading &&
        channels.map((channel) => {
          const challenge = challenges.find((item) => item.channel === channel)
          const current = channel === 'EMAIL' ? user?.email : user?.phone
          const verified = channel === 'EMAIL' ? user?.emailVerified : user?.phoneVerified
          const wait = challenge
            ? now === 0
              ? 60
              : Math.max(0, Math.ceil((Date.parse(challenge.resendAfter) - now) / 1000))
            : 0
          const expired =
            challenge && (!challenge.usable || (now > 0 && Date.parse(challenge.expiresAt) <= now))

          return (
            <section
              key={channel}
              className="flex flex-col justify-between p-6 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
            >
              <div>
                {/* Section Header */}
                <div className="flex items-center justify-between border-b border-slate-100 pb-4 dark:border-slate-800">
                  <div className="flex items-center gap-2.5">
                    <div
                      className={`flex size-9 items-center justify-center rounded-xl ${
                        channel === 'EMAIL'
                          ? 'bg-emerald-50 text-emerald-600'
                          : 'bg-blue-50 text-blue-600'
                      }`}
                    >
                      {channel === 'EMAIL' ? <Mail size={18} /> : <Phone size={18} />}
                    </div>
                    <div>
                      <h2 className="text-base font-bold text-slate-900 dark:text-white">
                        {labels[channel]}
                      </h2>
                      <p className="text-xs text-slate-400">
                        {channel === 'EMAIL'
                          ? 'Dùng để đăng nhập & nhận thư điện tử'
                          : 'Dùng để nhận SMS mã bảo mật OTP'}
                      </p>
                    </div>
                  </div>

                  {verified ? (
                    <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2.5 py-0.5 text-xs font-semibold text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-300">
                      <CheckCircle2 size={12} /> Đã xác thực
                    </span>
                  ) : (
                    <span className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2.5 py-0.5 text-xs font-semibold text-amber-700 dark:bg-amber-950/60 dark:text-amber-300">
                      <AlertCircle size={12} /> Chưa xác thực
                    </span>
                  )}
                </div>

                {/* Current Value Display */}
                <div className="mt-4 rounded-2xl bg-slate-50 p-3.5 dark:bg-slate-800/60">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-400 block">
                    ĐANG SỬ DỤNG
                  </span>
                  <p className="text-sm font-bold text-slate-800 dark:text-slate-100 mt-0.5 break-all">
                    {current || 'Chưa thiết lập'}
                  </p>
                </div>

                {/* Change Request Form */}
                <form
                  onSubmit={(event) => requestChange(event, channel)}
                  className="mt-5 space-y-4"
                >
                  <div className="space-y-1.5">
                    <label className="text-xs font-semibold text-slate-700 dark:text-slate-300">
                      Cập nhật {labels[channel].toLowerCase()} mới
                    </label>
                    <Input
                      name="recipient"
                      type={channel === 'EMAIL' ? 'email' : 'tel'}
                      placeholder={channel === 'EMAIL' ? 'tenban@example.com' : '+84901234567'}
                      maxLength={channel === 'EMAIL' ? 254 : 16}
                      pattern={channel === 'PHONE' ? '\\+[1-9][0-9]{7,14}' : undefined}
                      required
                      disabled={!!busy}
                      className="h-10 rounded-xl text-sm"
                    />
                    <p className="text-[11px] text-slate-400">
                      {channel === 'PHONE' && 'Lưu ý: Nhập kèm mã quốc gia (ví dụ: +84901234567).'}
                    </p>
                  </div>

                  <Button
                    type="submit"
                    variant="outline"
                    disabled={!!busy}
                    className="w-full h-10 font-semibold text-xs border-slate-200 hover:bg-slate-50 hover:text-emerald-600 transition-colors"
                  >
                    {busy === channel ? (
                      <>
                        <RefreshCw size={14} className="animate-spin mr-1.5" />
                        Đang gửi yêu cầu…
                      </>
                    ) : (
                      <>
                        <Send size={13} className="mr-1.5" />
                        Gửi mã xác minh {labels[channel]} mới
                      </>
                    )}
                  </Button>
                </form>
              </div>

              {/* Pending Challenge Box */}
              {challenge && (
                <div className="mt-6 rounded-2xl border border-emerald-200 bg-emerald-50/70 p-4 dark:border-emerald-800/50 dark:bg-emerald-950/30">
                  <div className="flex items-center justify-between text-xs font-bold text-emerald-900 dark:text-emerald-200">
                    <span>MÃ XÁC MINH OTP ĐÃ GỬI</span>
                    <span className="text-[11px] font-normal text-slate-500">
                      Hết hạn: {new Date(challenge.expiresAt).toLocaleTimeString('vi-VN')}
                    </span>
                  </div>
                  <p className="mt-1 text-xs text-slate-600 dark:text-slate-300 break-all">
                    Đã gửi mã 6 chữ số đến:{' '}
                    <strong className="font-semibold text-emerald-800 dark:text-emerald-300">
                      {challenge.recipient}
                    </strong>
                  </p>

                  {expired ? (
                    <div className="mt-3">
                      <p className="text-xs text-rose-600 font-medium">
                        Mã OTP đã hết hiệu lực. Vui lòng bấm gửi lại mã mới bên dưới.
                      </p>
                    </div>
                  ) : (
                    <form
                      key={challenge.id}
                      onSubmit={(event) => verify(event, challenge)}
                      className="mt-3 flex gap-2"
                    >
                      <Input
                        name="code"
                        inputMode="numeric"
                        autoComplete="one-time-code"
                        pattern="[0-9]{6}"
                        minLength={6}
                        maxLength={6}
                        placeholder="000000"
                        required
                        disabled={!!busy}
                        className="h-10 text-center font-mono tracking-widest text-base font-bold rounded-xl bg-white dark:bg-slate-900"
                      />
                      <Button
                        type="submit"
                        disabled={!!busy}
                        className="h-10 rounded-full bg-emerald-600 text-white font-semibold text-xs px-4 hover:bg-emerald-600 shrink-0"
                      >
                        {busy === challenge.channel ? (
                          <RefreshCw size={14} className="animate-spin" />
                        ) : (
                          'Xác minh'
                        )}
                      </Button>
                    </form>
                  )}

                  <div className="mt-3 flex items-center justify-between pt-2 border-t border-emerald-200/60 dark:border-emerald-800/40">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={!!busy || wait > 0}
                      onClick={() => resend(challenge)}
                      className="text-xs text-emerald-700 hover:text-emerald-800 dark:text-emerald-300 p-0 h-auto font-medium"
                    >
                      {wait > 0 ? `Gửi lại mã sau (${wait}s)` : 'Chưa nhận được? Gửi lại mã'}
                    </Button>
                  </div>
                </div>
              )}
            </section>
          )
        })}
    </div>
  )
}
