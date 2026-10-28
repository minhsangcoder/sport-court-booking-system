'use client'
import { titles } from './auth-mode'
import type { AuthModel } from './use-auth-form'

export function AuthHeading({ model }: { model: AuthModel }) {
  const { mode, switchMode, applyAsOwner } = model
  return (
    <>
      {(mode === 'login' || mode === 'register') && !applyAsOwner && (
        <div className="relative mb-5 flex rounded-lg border border-slate-200 bg-slate-100 p-1 text-xs font-semibold dark:bg-slate-800/80 select-none">
          <div
            className={`absolute top-1 bottom-1 w-[calc(50%-4px)] rounded-md bg-white shadow-2xs transition-all duration-200 dark:bg-slate-900 ${
              mode === 'login' ? 'left-1' : 'left-[calc(50%+2px)]'
            }`}
          />
          <button
            type="button"
            onClick={() => switchMode('login')}
            className={`relative z-10 flex-1 flex items-center justify-center rounded-md py-1.5 transition-colors duration-200 cursor-pointer ${
              mode === 'login'
                ? 'text-slate-900 font-semibold dark:text-white'
                : 'text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'
            }`}
          >
            Đăng nhập
          </button>
          <button
            type="button"
            onClick={() => switchMode('register')}
            className={`relative z-10 flex-1 flex items-center justify-center rounded-md py-1.5 transition-colors duration-200 cursor-pointer ${
              mode === 'register'
                ? 'text-slate-900 font-semibold dark:text-white'
                : 'text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'
            }`}
          >
            Đăng ký
          </button>
        </div>
      )}

      <div key={mode} className="mb-5 space-y-1 auth-fade-in">
        <h1 className="text-lg sm:text-xl font-bold tracking-tight text-slate-900 dark:text-white">
          {titles[mode]}
        </h1>
        <p className="text-xs text-slate-500 dark:text-slate-400">
          {mode === 'login'
            ? 'Đăng nhập để đặt sân nhanh, quản lý lịch đấu và nhận ưu đãi.'
            : mode === 'register'
              ? 'Tạo tài khoản SportHub để tham gia cộng đồng thể thao sôi động.'
              : mode === 'verify'
                ? 'Nhập mã 6 chữ số gửi về thư điện tử hoặc điện thoại để kích hoạt.'
                : 'Nhập thông tin xác thực để tiếp tục.'}
        </p>
      </div>
    </>
  )
}
