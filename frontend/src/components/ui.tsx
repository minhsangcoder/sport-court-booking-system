"use client";
import Link from "next/link";
import {
  useEffect,
  useId,
  useRef,
  type ReactNode,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type SelectHTMLAttributes,
} from "react";
import {
  ArrowRight,
  ArrowUpRight,
  Check,
  CheckCircle2,
  Inbox,
  LoaderCircle,
  RefreshCw,
  TriangleAlert,
  X,
} from "lucide-react";
import { label } from "@/rebuilt/lib/domain";
export function Button({
  children,
  variant = "primary",
  className = "",
  busy,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "ghost" | "danger";
  busy?: boolean;
}) {
  return (
    <button
      {...props}
      disabled={busy || props.disabled}
      className={`btn btn-${variant} ${className}`}
    >
      {busy && <LoaderCircle className="spin" size={17} />} {children}
    </button>
  );
}
export function LinkButton({
  href,
  children,
  variant = "primary",
  arrow = false,
}: {
  href: string;
  children: ReactNode;
  variant?: string;
  arrow?: boolean;
}) {
  return (
    <Link href={href} className={`btn btn-${variant}`}>
      {children}
      {arrow && <ArrowUpRight size={17} />}
    </Link>
  );
}
export function Badge({
  state,
  children,
}: {
  state?: string;
  children?: ReactNode;
}) {
  const tone =
    state &&
    ([
      "ACTIVE",
      "AVAILABLE",
      "CONFIRMED",
      "SUCCESS",
      "PAID",
      "APPROVED",
      "COMPLETED",
    ].includes(state)
      ? "green"
      : ["CANCELLED", "FAILED", "REJECTED", "LOCKED"].includes(state)
        ? "red"
        : [
              "PENDING",
              "PENDING_APPROVAL",
              "HOLD",
              "HELD",
              "SUPPLEMENT_REQUIRED",
              "GROUP_PENDING",
            ].includes(state)
          ? "amber"
          : "neutral");
  return (
    <span className={`badge badge-${tone ?? "neutral"}`}>
      <span className="badge-dot" />
      {children ?? label(state ?? "")}
    </span>
  );
}
export function PageHead({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <header className="page-head">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {description && <p className="muted">{description}</p>}
      </div>
      {action}
    </header>
  );
}
export function Panel({
  children,
  title,
  description,
  action,
  className = "",
}: {
  children: ReactNode;
  title?: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <section className={`panel ${className}`}>
      {title && (
        <div className="panel-head">
          <div>
            <h2>{title}</h2>
            {description && <p className="muted small">{description}</p>}
          </div>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}
export function Notice({
  children,
  tone = "info",
}: {
  children: ReactNode;
  tone?: "info" | "error" | "success" | "warning";
}) {
  return (
    <div
      className={`notice notice-${tone}`}
      role={tone === "error" ? "alert" : "status"}
    >
      {tone === "success" ? (
        <CheckCircle2 size={18} />
      ) : (
        <TriangleAlert size={18} />
      )}
      <div>{children}</div>
    </div>
  );
}
export function Feedback({
  action,
}: {
  action: { error: string; message: string };
}) {
  return (
    <>
      {action.error && <Notice tone="error">{action.error}</Notice>}
      {action.message && <Notice tone="success">{action.message}</Notice>}
    </>
  );
}
export function ResourceState({
  resource,
  empty = false,
  emptyTitle = "Chưa có dữ liệu",
  emptyDescription = "Dữ liệu sẽ xuất hiện tại đây khi có hoạt động.",
  children,
}: {
  resource: { loading: boolean; error: string; reload: () => void };
  empty?: boolean;
  emptyTitle?: string;
  emptyDescription?: string;
  children: ReactNode;
}) {
  if (resource.loading)
    return (
      <div className="loading-state" role="status">
        <LoaderCircle className="spin" size={24} />
        <p>Đang tải dữ liệu…</p>
        <div className="skeleton-lines">
          <i />
          <i />
          <i />
        </div>
      </div>
    );
  if (resource.error)
    return (
      <div className="empty-state">
        <TriangleAlert size={32} />
        <h3>Không tải được dữ liệu</h3>
        <p>{resource.error}</p>
        <Button variant="secondary" onClick={resource.reload}>
          <RefreshCw size={16} />
          Thử lại
        </Button>
      </div>
    );
  if (empty) return <Empty title={emptyTitle} description={emptyDescription} />;
  return <>{children}</>;
}
export function Empty({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <Inbox size={32} />
      <h3>{title}</h3>
      <p>{description}</p>
      {action}
    </div>
  );
}
export function Field({
  label: caption,
  error,
  hint,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  error?: string;
  hint?: string;
}) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>
        {caption}
        {props.required && <span aria-hidden="true"> *</span>}
      </label>
      <input
        id={id}
        {...props}
        aria-invalid={!!error}
        aria-describedby={error || hint ? id + "-hint" : undefined}
      />
      {(error || hint) && (
        <small id={id + "-hint"} className={error ? "field-error" : "muted"}>
          {error || hint}
        </small>
      )}
    </div>
  );
}
export function Select({
  label: caption,
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & { label: string }) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{caption}</label>
      <select {...props} id={id}>
        {children}
      </select>
    </div>
  );
}
export function TextArea({
  label: caption,
  name,
  defaultValue,
  required = false,
  maxLength = 5000,
  minLength,
  error,
}: {
  label: string;
  name: string;
  defaultValue?: string;
  required?: boolean;
  maxLength?: number;
  minLength?: number;
  error?: string;
}) {
  const id = useId();
  return (
    <div className="field span-2">
      <label htmlFor={id}>
        {caption}
        {required ? " *" : ""}
      </label>
      <textarea
        id={id}
        name={name}
        defaultValue={defaultValue}
        required={required}
        maxLength={maxLength}
        minLength={minLength}
        rows={3}
        aria-invalid={!!error}
      />
      {error && <small className="field-error">{error}</small>}
    </div>
  );
}
export function Tabs({
  items,
  value,
  onChange,
  label: caption = "Chọn chế độ",
}: {
  items: [string, string][];
  value: string;
  onChange: (v: string) => void;
  label?: string;
}) {
  return (
    <div className="tabs" aria-label={caption}>
      {items.map(([id, text]) => (
        <button
          key={id}
          type="button"
          aria-pressed={value === id}
          className={value === id ? "active" : ""}
          onClick={() => onChange(id)}
        >
          {text}
        </button>
      ))}
    </div>
  );
}
export function Modal({
  open,
  title,
  children,
  onClose,
}: {
  open: boolean;
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null),
    id = useId();
  useEffect(() => {
    const el = ref.current;
    if (open && !el?.open) el?.showModal();
    if (!open && el?.open) el.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      aria-labelledby={id}
      onCancel={onClose}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="modal-head">
        <h2 id={id}>{title}</h2>
        <button
          type="button"
          className="icon-btn"
          onClick={onClose}
          aria-label="Đóng"
        >
          <X size={20} />
        </button>
      </div>
      {children}
    </dialog>
  );
}
export function Stepper({
  current,
  steps = ["Chọn lịch", "Xác nhận", "Thanh toán"],
}: {
  current: number;
  steps?: string[];
}) {
  return (
    <ol className="stepper">
      {steps.map((step, i) => (
        <li
          key={step}
          className={i <= current ? "active" : ""}
          aria-current={i === current ? "step" : undefined}
        >
          <span>{i < current ? <Check size={14} /> : i + 1}</span>
          {step}
          {i < steps.length - 1 && <ArrowRight size={14} />}
        </li>
      ))}
    </ol>
  );
}
export function Stat({
  label: caption,
  value,
  detail,
  icon,
}: {
  label: string;
  value: ReactNode;
  detail?: string;
  icon?: ReactNode;
}) {
  return (
    <div className="stat">
      <div>
        <span className="muted small">{caption}</span>
        {icon}
      </div>
      <strong>{value}</strong>
      {detail && <p className="muted small">{detail}</p>}
    </div>
  );
}
export function DetailList({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="detail-list">
      {items.map(([key, value]) => (
        <div key={key}>
          <dt>{key}</dt>
          <dd>{value ?? "Chưa cung cấp"}</dd>
        </div>
      ))}
    </dl>
  );
}
export function Table({
  headers,
  children,
}: {
  headers: string[];
  children: ReactNode;
}) {
  return (
    <div
      className="table-scroll"
      tabIndex={0}
      aria-label="Bảng dữ liệu, có thể cuộn ngang"
    >
      <table>
        <thead>
          <tr>
            {headers.map((x) => (
              <th key={x}>{x}</th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}
export function ExportButton({
  rows,
  name = "sporthub-report",
}: {
  rows: Record<string, unknown>[];
  name?: string;
}) {
  return (
    <Button
      variant="secondary"
      disabled={!rows.length}
      onClick={() => {
        const columns = Array.from(
          new Set(rows.flatMap((row) => Object.keys(row))),
        );
        const quote = (v: unknown) =>
          '"' +
          String(v ?? "")
            .replace(/^[=+@-]/, "'$&")
            .replaceAll('"', '""') +
          '"';
        const csv =
          "\ufeff" +
          [
            columns.map(quote).join(","),
            ...rows.map((row) => columns.map((k) => quote(row[k])).join(",")),
          ].join("\r\n");
        const url = URL.createObjectURL(
          new Blob([csv], { type: "text/csv;charset=utf-8" }),
        );
        const a = document.createElement("a");
        a.href = url;
        a.download = name + ".csv";
        a.click();
        URL.revokeObjectURL(url);
      }}
    >
      Xuất CSV
    </Button>
  );
}
