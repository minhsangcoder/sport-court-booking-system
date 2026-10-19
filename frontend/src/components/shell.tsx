"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState, type ReactNode } from "react";
import {
  Activity,
  ArrowUpRight,
  Building2,
  CalendarDays,
  ChartNoAxesCombined,
  ChevronDown,
  Compass,
  ContactRound,
  LayoutDashboard,
  LogOut,
  Menu,
  ShieldCheck,
  Ticket,
  Users,
  Wallet,
  X,
} from "lucide-react";
import { authApi, type Role } from "@/rebuilt/lib/api";
import { useAuth } from "@/rebuilt/lib/auth-context";
import { useResource } from "@/rebuilt/lib/hooks";
import type { Facility } from "@/rebuilt/lib/domain";
import { Button, Empty, LinkButton, Notice } from "./ui";
export function Brand({ light = false }: { light?: boolean }) {
  return (
    <Link
      href="/"
      className={`brand ${light ? "brand-light" : ""}`}
      aria-label="SportHub — Trang chủ"
    >
      <span className="brand-mark">
        <i />
        <i />
        <i />
      </span>
      sporthub<span className="brand-period">.</span>
    </Link>
  );
}
export function Guard({
  children,
  roles,
}: {
  children: ReactNode;
  roles?: Role[];
}) {
  const { user, ready } = useAuth(),
    router = useRouter();
  if (!ready)
    return (
      <div className="loading-state" role="status">
        Đang kiểm tra phiên đăng nhập…
      </div>
    );
  if (!user)
    return (
      <Empty
        title="Một tài khoản, mọi cuộc chơi."
        description="Đăng nhập để đặt sân và tiếp tục theo dõi lịch chơi của bạn."
        action={
          <Button
            onClick={() =>
              router.push(
                `/login?next=${encodeURIComponent(window.location.pathname + window.location.search)}`,
              )
            }
          >
            Đăng nhập để tiếp tục
          </Button>
        }
      />
    );
  if (roles && !roles.some((x) => user.roles.includes(x)))
    return (
      <Notice tone="error">
        Tài khoản của bạn chưa được cấp quyền truy cập khu vực này.{" "}
        <Link href="/">Về trang chủ</Link>
      </Notice>
    );
  return <>{children}</>;
}
export function Shell({ children }: { children: ReactNode }) {
  const path = usePathname();
  if (/^\/(owner|staff|admin)(\/|$)/.test(path))
    return <ManagementShell>{children}</ManagementShell>;
  return <PublicShell>{children}</PublicShell>;
}
function UserMenu() {
  const { user } = useAuth();
  const path = usePathname();
  const [open, setOpen] = useState(false),
    [error, setError] = useState(""),
    router = useRouter();
  if (!user)
    return (
      <div className="header-auth">
        <Link className="text-link" href="/login">
          Đăng nhập
        </Link>
        <LinkButton href="/register" variant="dark">
          Bắt đầu ngay <ArrowUpRight size={15} />
        </LinkButton>
      </div>
    );
  return (
    <div className="user-menu">
      <button
        className="user-trigger"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
      >
        <span className="avatar">{user.fullName.slice(0, 1)}</span>
        <span>{user.fullName.split(" ").slice(-2).join(" ")}</span>
        <ChevronDown size={14} />
      </button>
      {open && (
        <>
          <button
            className="menu-dismiss"
            aria-label="Đóng menu"
            onClick={() => setOpen(false)}
          />
          <div className="dropdown">
            <Link onClick={() => setOpen(false)} href="/customer/profile">
              <ContactRound size={17} />
              Tài khoản
            </Link>
            {(["OWNER", "STAFF", "ADMIN"] as Role[])
              .filter((x) => user.roles.includes(x) && !path.startsWith(`/${x.toLowerCase()}`))
              .map((role) => (
                <Link
                  onClick={() => setOpen(false)}
                  key={role}
                  href={`/${role.toLowerCase()}`}
                >
                  <LayoutDashboard size={17} />
                  {role === "OWNER"
                    ? "Quản lý cơ sở"
                    : role === "STAFF"
                      ? "Vận hành sân"
                      : "Quản trị nền tảng"}
                </Link>
              ))}
            {!user.roles.includes("OWNER") && !path.startsWith("/owner/") && (
              <Link onClick={() => setOpen(false)} href="/owner/apply">
                <Building2 size={17} />
                Đăng ký chủ sân
              </Link>
            )}
            <button
              onClick={() => {
                void authApi
                  .logout()
                  .then(() => {
                    setOpen(false);
                    router.push("/");
                  })
                  .catch((e) => setError(e.message));
              }}
            >
              <LogOut size={17} />
              Đăng xuất
            </button>
            {error && <p role="alert">{error}</p>}
          </div>
        </>
      )}
    </div>
  );
}
function PublicShell({ children }: { children: ReactNode }) {
  const path = usePathname(),
    [open, setOpen] = useState(false);
  const { user } = useAuth();
  const personal = path.startsWith("/customer/");
  const auth = [
    "/login",
    "/register",
    "/forgot-password",
    "/reset-password",
    "/verify",
  ].includes(path);
  return (
    <div className="public-shell">
      <a className="skip-link" href="#content">
        Đến nội dung chính
      </a>
      <header className="public-header">
        <div className="header-inner">
          <Brand />
          <nav
            id="public-navigation"
            className={`public-nav ${open ? "is-open" : ""}`}
            aria-label="Điều hướng chính"
          >
            <Link
              onClick={() => setOpen(false)}
              className={path === "/search" ? "active" : ""}
              aria-current={path === "/search" ? "page" : undefined}
              href="/search"
            >
              Tìm sân
            </Link>
            <Link
              onClick={() => setOpen(false)}
              className={path.startsWith("/transfers") ? "active" : ""}
              aria-current={path.startsWith("/transfers") ? "page" : undefined}
              href="/transfers"
            >
              Chợ chuyển nhượng
            </Link>
            {user && !personal && (
              <Link onClick={() => setOpen(false)} href="/customer/bookings">
                Lịch của tôi
              </Link>
            )}
          </nav>
          <UserMenu />
          <button
            className="icon-btn mobile-menu"
            aria-label={open ? "Đóng điều hướng" : "Mở điều hướng"}
            aria-expanded={open}
            aria-controls="public-navigation"
            onClick={() => setOpen(!open)}
          >
            {open ? <X /> : <Menu />}
          </button>
        </div>
      </header>
      {personal && (
        <nav className="customer-nav" aria-label="Không gian cá nhân">
          <Link
            href="/customer/bookings"
            className={path.includes("/booking") ? "active" : ""}
            aria-current={path.includes("/booking") ? "page" : undefined}
          >
            <Ticket size={17} />
            Lịch của tôi
          </Link>
          <Link
            href="/customer/groups"
            className={path.includes("/groups") ? "active" : ""}
            aria-current={path.includes("/groups") ? "page" : undefined}
          >
            <Users size={17} />
            Nhóm chơi
          </Link>
          <Link
            href="/customer/transfers"
            className={path.includes("/transfers") ? "active" : ""}
            aria-current={path.includes("/transfers") ? "page" : undefined}
          >
            <Activity size={17} />
            Tin chuyển nhượng của tôi
          </Link>
        </nav>
      )}
      <main id="content" className={auth ? "auth-main" : ""}>
        {children}
      </main>
      {!auth && (
        <footer className="footer">
          <div>
            <Brand />
            <p>Tìm sân, đặt lịch và chơi cùng nhau.</p>
          </div>
          {!user?.roles.includes("OWNER") && <Link className="text-link" href="/owner/apply">Dành cho chủ sân <ArrowUpRight size={14} /></Link>}
          <div className="footer-bottom">
            <span>© {new Date().getFullYear()} SportHub</span>
            <span>Đến sân. Vào cuộc.</span>
          </div>
        </footer>
      )}
    </div>
  );
}
function ManagementShell({ children }: { children: ReactNode }) {
  const path = usePathname(),
    router = useRouter(),
    [open, setOpen] = useState(false),
    { user, ready } = useAuth();
  const kind = path.split("/")[1],
    isOwner = kind === "owner",
    isStaff = kind === "staff";
  const facilities = useResource<Facility[]>(
    ready && user?.roles.includes("OWNER") && isOwner
      ? "/owner/facilities"
      : null,
  );
  const facilityId = path.match(/\/facilities\/([^/]+)/)?.[1];
  const selected =
    facilities.data?.find((f) => f.id === facilityId) || facilities.data?.[0];
  const iconSize = 18;
  const items: NavItem[] = isOwner
    ? user?.roles.includes("OWNER")
      ? [
          ["Quản lý chung", ""],
          ["Tổng quan", "/owner", <LayoutDashboard size={iconSize} key="icon" />],
          ["Cơ sở của tôi", "/owner/facilities", <Building2 size={iconSize} key="icon" />],
          ["Lịch đặt sân", "/owner/bookings", <CalendarDays size={iconSize} key="icon" />],
          ["Hồ sơ", ""],
          ["Hồ sơ chủ sân", "/owner/application", <ShieldCheck size={iconSize} key="icon" />],
        ]
      : [
          ["Chủ sân", ""],
          ["Đăng ký chủ sân", "/owner/apply", <Building2 size={iconSize} key="icon" />],
          ["Hồ sơ đăng ký", "/owner/application", <ShieldCheck size={iconSize} key="icon" />],
        ]
    : isStaff
      ? [
          ["Vận hành", ""],
          ["Lịch đặt sân", "/staff/bookings", <Ticket size={iconSize} key="icon" />],
          ["Lịch sân", "/staff/schedule", <CalendarDays size={iconSize} key="icon" />],
          ["Đặt sân tại quầy", "/staff/counter-booking", <ContactRound size={iconSize} key="icon" />],
        ]
      : [
          ["Tổng quan", ""],
          ["Tổng quan và báo cáo", "/admin", <ChartNoAxesCombined size={iconSize} key="icon" />],
          ["Quản lý", ""],
          ["Tài khoản", "/admin/accounts", <Users size={iconSize} key="icon" />],
          ["Hồ sơ chủ sân", "/admin/owner-applications", <ShieldCheck size={iconSize} key="icon" />],
          ["Cơ sở thể thao", "/admin/facilities", <Building2 size={iconSize} key="icon" />],
          ["Bộ môn thể thao", "/admin/sports", <Activity size={iconSize} key="icon" />],
          ["Hoạt động", ""],
          ["Lịch đặt sân", "/admin/bookings", <Ticket size={iconSize} key="icon" />],
          ["Thanh toán", "/admin/payments", <Wallet size={iconSize} key="icon" />],
          ["Đối soát", "/admin/bookings/reconciliation", <Activity size={iconSize} key="icon" />],
          ["Khiếu nại", "/admin/disputes", <ContactRound size={iconSize} key="icon" />],
        ];
  // Root Staff and the report URL are alternate routes to existing screens.
  const navigationPath = path === "/staff" ? "/staff/bookings"
    : path === "/admin/reports" ? "/admin" : path;
  const activeItem = items
    .filter(([, href]) => href && (navigationPath === href ||
      (href !== `/${kind}` && navigationPath.startsWith(href + "/"))))
    .sort((a, b) => b[1].length - a[1].length)[0];
  return (
    <div className="management-shell">
      <a className="skip-link" href="#content">
        Đến nội dung chính
      </a>
      {open && (
        <button
          className="sidebar-overlay"
          aria-label="Đóng điều hướng"
          onClick={() => setOpen(false)}
        />
      )}
      <aside id="workspace-navigation" className={`sidebar ${open ? "is-open" : ""}`}>
        <Brand light />
        <span className="workspace-label">
          {isOwner
            ? "Khu vực chủ sân"
            : isStaff
              ? "Khu vực nhân viên"
              : "Quản trị hệ thống"}
        </span>
        {isOwner && facilities.data && (
          <div className="facility-switch">
            <Building2 size={17} />
            <select
              aria-label="Chọn cơ sở"
              value={selected?.id ?? ""}
              onChange={(e) =>
                router.push(`/owner/facilities/${e.target.value}`)
              }
            >
              {facilities.data.length === 0 && (
                <option value="">Chưa có cơ sở</option>
              )}
              {facilities.data.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.name}
                </option>
              ))}
            </select>
          </div>
        )}
        <nav aria-label="Điều hướng quản lý">
          {items.map(([text, href, icon]) =>
            href ? (
              <Link
                onClick={() => setOpen(false)}
                href={href}
                key={text}
                className={activeItem?.[1] === href ? "active" : ""}
                aria-current={activeItem?.[1] === href ? "page" : undefined}
              >
                {icon}
                {text}
              </Link>
            ) : (
              <span className="nav-section" key={text}>
                {text}
              </span>
            ),
          )}
        </nav>
        <div className="sidebar-bottom">
          <Link href="/">
            <Compass size={18} />
            Khám phá SportHub <ArrowUpRight size={15} />
          </Link>
          <div className="sidebar-user">
            <span className="avatar">{user?.fullName.slice(0, 1) ?? "S"}</span>
            <div>
              <strong>{user?.fullName ?? "SportHub"}</strong>
              <span>
                {isOwner
                  ? "Đối tác sân thể thao"
                  : isStaff
                    ? "Nhân viên vận hành"
                    : "Quản trị nền tảng"}
              </span>
            </div>
          </div>
        </div>
      </aside>
      <div className="workspace">
        <header className="workspace-header">
          <button
            className="icon-btn mobile-menu"
            onClick={() => setOpen(true)}
            aria-label="Mở điều hướng"
            aria-controls="workspace-navigation"
            aria-expanded={open}
          >
            <Menu />
          </button>
          <div className="breadcrumb">
            {isOwner
              ? "Quản lý cơ sở"
              : isStaff
                ? "Vận hành sân"
                : "Quản trị nền tảng"}
            <span>/</span>
            <strong>
              {activeItem?.[0] ?? "Chi tiết"}
            </strong>
          </div>
          <UserMenu />
        </header>
        <main id="content" className="workspace-content">
          {children}
        </main>
      </div>
    </div>
  );
}
type NavItem = [string, string, ReactNode?];
