"use client";
import { Photo } from "@/rebuilt/components/photo";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { ArrowRight, Bell, Copy, Plus, Users, Wallet } from "lucide-react";
import { api } from "@/rebuilt/lib/api";
import { useAuth } from "@/rebuilt/lib/auth-context";
import { useAction, useIdempotency, useResource } from "@/rebuilt/lib/hooks";
import {
  type Group,
  type Invite,
  type Payment,
  dateTime,
  label,
  money,
  shortId,
} from "@/rebuilt/lib/domain";
import {
  Badge,
  Button,
  DetailList,
  Feedback,
  Field,
  LinkButton,
  Modal,
  Notice,
  PageHead,
  Panel,
  ResourceState,
  Stat,
  Tabs,
  TextArea,
} from "@/rebuilt/components/ui";
export function GroupsPage() {
  const r = useResource<Group[]>("/groups");
  return (
    <div className="container">
      <PageHead
        eyebrow="Nhóm chơi"
        title="Cùng nhóm, thêm vui."
        description="Lịch chơi chung, chi phí rõ ràng và đồng đội luôn kết nối."
        action={
          <div className="actions">
            <LinkButton href="/customer/groups/join" variant="secondary">
              Nhập mã mời
            </LinkButton>
            <LinkButton href="/search" arrow>
              Tạo cuộc chơi mới
            </LinkButton>
          </div>
        }
      />
      <ResourceState
        resource={r}
        empty={r.data?.length === 0}
        emptyTitle="Chưa có nhóm chơi"
        emptyDescription="Chọn sân và chọn đặt sân theo nhóm, hoặc tham gia qua mã mời."
      >
        <div className="grid-3">
          {r.data?.map((g) => (
            <Panel key={g.id}>
              <div
                className="actions"
                style={{ justifyContent: "space-between", marginBottom: 18 }}
              >
                <span className="sport-icon">
                  <Users size={24} />
                </span>
                <Badge state={g.state} />
              </div>
              <h3>{g.name || `Nhóm #${shortId(g.id)}`}</h3>
              <p className="muted small" style={{ margin: "10px 0" }}>
                {dateTime(g.booking.startsAt, g.booking.priceSnapshot.timezone)}{" "}
                · {g.members.filter((m) => m.active).length}/{g.maxMembers}{" "}
                người
              </p>
              <div className="progress-track">
                <div
                  style={{
                    width: `${Math.min(100, (g.totalPaid / g.booking.amount) * 100)}%`,
                  }}
                />
              </div>
              <div
                className="actions"
                style={{ justifyContent: "space-between", marginBottom: 22 }}
              >
                <span className="muted small">
                  Đã đóng {money(g.totalPaid)}
                </span>
                <strong className="small">{money(g.booking.amount)}</strong>
              </div>
              <LinkButton
                href={`/customer/groups/${g.id}`}
                variant="secondary"
                arrow
              >
                Xem nhóm chơi
              </LinkButton>
            </Panel>
          ))}
        </div>
      </ResourceState>
    </div>
  );
}
export function GroupPage({
  id,
  mode = "detail",
}: {
  id: string;
  mode?: "detail" | "split" | "invite";
}) {
  const r = useResource<Group>(`/groups/${id}`);
  return (
    <div className="container">
      <ResourceState resource={r}>
        {r.data && (
          <GroupWorkspace group={r.data} reload={r.reload} mode={mode} />
        )}
      </ResourceState>
    </div>
  );
}
function GroupWorkspace({
  group: g,
  reload,
  mode,
}: {
  group: Group;
  reload: () => void;
  mode: string;
}) {
  const { user } = useAuth(),
    owner = g.ownerId === user?.id,
    action = useAction(),
    router = useRouter(),
    key = useIdempotency(),
    [filter, setFilter] = useState("all"),
    [splitMode, setSplitMode] = useState("EQUAL"),
    [amounts, setAmounts] = useState<Record<string, string>>({}),
    [selected, setSelected] = useState<string[]>([]),
    [reminder, setReminder] = useState<"selection" | "all" | null>(null),
    [cancel, setCancel] = useState(false),
    [leave, setLeave] = useState(false);
  const invite = useResource<Invite>(
    mode === "invite" && owner ? `/groups/${g.id}/invite` : null,
  );
  const pending = g.state === "GROUP_PENDING",
    mutable =
      pending &&
      !g.members.some((m) => m.amountPaid > 0 || m.paymentRequestedAt),
    active = g.members.filter((m) => m.active),
    eligible = active.filter((m) => m.reminderEligible),
    visible = active.filter(
      (m) =>
        filter === "all" ||
        (filter === "paid" &&
          m.amountPaid >= m.amountDue &&
          g.allocationsLocked) ||
        (filter === "unpaid" && m.amountPaid < m.amountDue),
    );
  async function pay(memberId: string) {
    await action.run(
      () =>
        api<Payment>("/payments", {
          method: "POST",
          headers: { "Idempotency-Key": key("pay:" + g.id + ":" + memberId) },
          body: JSON.stringify({ bookingId: g.id, memberId }),
        }),
      (p) => router.push(`/customer/payments/${p.id}`),
    );
  }
  async function split(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const allocations = active.map((m) => ({
      memberId: m.id,
      amount: Number(amounts[m.id] ?? m.amountDue),
    }));
    if (
      splitMode === "CUSTOM" &&
      allocations.reduce((s, x) => s + x.amount, 0) !== g.booking.amount
    ) {
      action.setError("Tổng chi phí phân bổ phải bằng giá trị lịch đặt.");
      return;
    }
    await action.run(
      () =>
        api(`/groups/${g.id}/allocations`, {
          method: "PUT",
          body: JSON.stringify({
            mode: splitMode,
            allocations: splitMode === "CUSTOM" ? allocations : null,
          }),
        }),
      () => router.push(`/customer/groups/${g.id}`),
    );
  }
  const recipients =
    reminder === "all"
      ? eligible
      : active.filter((m) => selected.includes(m.id));
  return (
    <>
      <PageHead
        eyebrow="Chi tiết nhóm"
        title={g.name || `Nhóm #${shortId(g.id)}`}
        description={`${dateTime(g.booking.startsAt, g.booking.priceSnapshot.timezone)} · Nhóm ${shortId(g.id)}`}
        action={
          <div className="actions">
            <Badge state={g.state} />
            <Button variant="secondary" onClick={reload}>
              Cập nhật
            </Button>
          </div>
        }
      />
      <nav className="settings-nav">
        <Link
          className={mode === "detail" ? "active" : ""}
          href={`/customer/groups/${g.id}`}
        >
          Nhóm & thanh toán
        </Link>
        {owner && (
          <>
            <Link
              className={mode === "invite" ? "active" : ""}
              href={`/customer/groups/${g.id}/invite`}
            >
              Mời thành viên
            </Link>
            <Link
              className={mode === "split" ? "active" : ""}
              href={`/customer/groups/${g.id}/split`}
            >
              Phân chia chi phí
            </Link>
          </>
        )}
        <Link href={`/customer/bookings/${g.id}`}>Vé & lịch sử booking</Link>
      </nav>
      <Feedback action={action} />
      <div className="grid-3" style={{ marginBottom: 26 }}>
        <Stat
          label="Tổng chi phí"
          value={money(g.booking.amount)}
          icon={<Wallet size={20} />}
        />
        <Stat
          label="Đã thanh toán"
          value={money(g.totalPaid)}
          detail={`Còn lại ${money(Math.max(0, g.booking.amount - g.totalPaid))}`}
        />
        <Stat
          label="Thành viên"
          value={`${active.length} / ${g.maxMembers}`}
          detail={`Hạn đóng góp: ${dateTime(g.deadline)}`}
          icon={<Users size={20} />}
        />
      </div>
      {mode === "invite" ? (
        <Panel title="Thêm đồng đội vào cuộc chơi">
          {!owner ? (
            <Notice tone="error">
              Chỉ người tạo booking được lấy lời mời.
            </Notice>
          ) : (
            <ResourceState resource={invite}>
              {invite.data && (
                <div className="grid-2">
                  <div className="stack">
                    <p className="muted small">
                      Lời mời còn hiệu lực đến {dateTime(invite.data.expiresAt)}
                      . Thành viên không thể tham gia khi đã chốt chi phí.
                    </p>
                    <Field
                      label="Liên kết mời"
                      value={
                        typeof window === "undefined"
                          ? invite.data.path
                          : new URL(invite.data.path, window.location.origin)
                              .href
                      }
                      readOnly
                    />
                    <Button
                      variant="secondary"
                      onClick={() => {
                        void action.run(
                          () =>
                            navigator.clipboard.writeText(
                              new URL(invite.data!.path, window.location.origin)
                                .href,
                            ),
                          () => action.setMessage("Đã sao chép liên kết mời."),
                        );
                      }}
                    >
                      <Copy size={16} />
                      Sao chép lời mời
                    </Button>
                    <details>
                      <summary className="small text-link">Xem mã mời</summary>
                      <p className="break-id" style={{ marginTop: 12 }}>
                        {invite.data.code}
                      </p>
                    </details>
                  </div>
                  <div className="ticket-qr" style={{ border: 0 }}>
                    <Photo
                      src={
                        invite.data.qrPngDataUrl ??
                        `data:image/svg+xml;charset=utf-8,${encodeURIComponent(invite.data.qrSvg)}`
                      }
                      alt="QR tham gia nhóm"
                    />
                    <p>Quét mã để mở lời mời</p>
                  </div>
                </div>
              )}
            </ResourceState>
          )}
        </Panel>
      ) : mode === "split" ? (
        <Panel
          title="Chia chi phí, rõ ràng với mọi người"
          description="Tổng phân bổ phải bằng tổng giá booking."
        >
          {!owner || !mutable ? (
            <Notice tone="warning">
              Chỉ người tổ chức được phân chia chi phí trước khi bắt đầu thanh
              toán. Backend hiện chưa hỗ trợ thay đổi phân bổ đã có giao dịch.
            </Notice>
          ) : (
            <form onSubmit={split}>
              <Tabs
                value={splitMode}
                onChange={setSplitMode}
                items={[
                  ["EQUAL", "Chia đều"],
                  ["CUSTOM", "Chia tùy chỉnh"],
                ]}
              />
              <div className="stack">
                {splitMode === "CUSTOM" ? (
                  active.map((m) => (
                    <Field
                      key={m.id}
                      label={`${m.displayName} (VND)`}
                      type="number"
                      min={0}
                      step={1}
                      max={g.booking.amount}
                      value={amounts[m.id] ?? String(m.amountDue)}
                      onChange={(e) =>
                        setAmounts({ ...amounts, [m.id]: e.target.value })
                      }
                      required
                    />
                  ))
                ) : (
                  <Notice>
                    Hệ thống sẽ chia đều {money(g.booking.amount)} cho{" "}
                    {active.length} người và xử lý phần dư theo quy tắc server.
                  </Notice>
                )}
              </div>
              <div className="form-footer">
                <p className="small muted">
                  Chi phí sẽ được chốt cho các thành viên hiện tại.
                </p>
                <Button busy={action.busy}>Xác nhận phân chia</Button>
              </div>
            </form>
          )}
        </Panel>
      ) : (
        <div className="detail-layout">
          <Panel
            title="Thành viên & đóng góp"
            action={
              owner && mutable ? (
                <LinkButton
                  href={`/customer/groups/${g.id}/invite`}
                  variant="secondary"
                >
                  <Plus size={15} />
                  Mời đồng đội
                </LinkButton>
              ) : undefined
            }
          >
            <div className="progress-track">
              <div
                style={{
                  width: `${Math.min(100, (g.totalPaid / g.booking.amount) * 100)}%`,
                }}
              />
            </div>
            <Tabs
              value={filter}
              onChange={setFilter}
              items={[
                ["all", "Tất cả"],
                ["unpaid", "Chưa đủ"],
                ["paid", "Đã thanh toán"],
              ]}
            />
            <div className="group-members">
              {visible.map((m) => (
                <div className="member-row" key={m.id}>
                  {owner && pending && g.allocationsLocked && (
                    <input
                      type="checkbox"
                      aria-label={`Chọn nhắc ${m.displayName}`}
                      checked={selected.includes(m.id)}
                      disabled={!m.reminderEligible || action.busy}
                      onChange={(e) =>
                        setSelected(
                          e.target.checked
                            ? [...selected, m.id]
                            : selected.filter((id) => id !== m.id),
                        )
                      }
                    />
                  )}
                  <span className="avatar">{m.displayName.slice(0, 1)}</span>
                  <div>
                    <strong>
                      {m.displayName}
                      {m.userId === g.ownerId ? " · Người tổ chức" : ""}
                    </strong>
                    <small>
                      {money(m.amountPaid)} / {money(m.amountDue)}
                    </small>
                    {m.lastReminderAt && (
                      <small>
                        Đã xếp hàng nhắc: {dateTime(m.lastReminderAt)}
                      </small>
                    )}
                    {m.nextReminderAt && !m.reminderEligible && (
                      <small>
                        Có thể nhắc lại: {dateTime(m.nextReminderAt)}
                      </small>
                    )}
                  </div>
                  <div>
                    <Badge state={m.paymentState} />
                    {pending &&
                      g.allocationsLocked &&
                      m.amountDue > m.amountPaid &&
                      (owner || m.userId === user?.id) && (
                        <Button
                          variant="ghost"
                          busy={action.busy}
                          onClick={() => pay(m.id)}
                        >
                          {m.userId === user?.id
                            ? "Thanh toán phần của tôi"
                            : "Trả thay"}
                        </Button>
                      )}
                  </div>
                </div>
              ))}
            </div>
            {owner && pending && g.allocationsLocked && (
              <div className="actions" style={{ marginTop: 22 }}>
                <Button
                  variant="secondary"
                  disabled={!selected.length}
                  onClick={() => setReminder("selection")}
                >
                  <Bell size={15} />
                  Nhắc đã chọn ({selected.length})
                </Button>
                <Button
                  variant="ghost"
                  disabled={!eligible.length}
                  onClick={() => setReminder("all")}
                >
                  Nhắc tất cả chưa thanh toán
                </Button>
              </div>
            )}
          </Panel>
          <Panel className="booking-aside" title="Cuộc chơi đã sẵn sàng?">
            <DetailList
              items={[
                ["Trạng thái", label(g.state)],
                [
                  "Thời gian",
                  dateTime(
                    g.booking.startsAt,
                    g.booking.priceSnapshot.timezone,
                  ),
                ],
                ["Hạn đóng góp", dateTime(g.deadline)],
                [
                  "Còn thiếu",
                  money(Math.max(0, g.booking.amount - g.totalPaid)),
                ],
              ]}
            />
            {!g.allocationsLocked && owner && pending && (
              <LinkButton href={`/customer/groups/${g.id}/split`} arrow>
                Chốt & chia chi phí
              </LinkButton>
            )}
            {g.allocationsLocked && (
              <Notice>
                Thành viên và chi phí đã được chốt. Thanh toán đủ trước hạn để
                hệ thống xác nhận booking.
              </Notice>
            )}
            {pending && mutable && (
              <Button
                variant="ghost"
                onClick={() => (owner ? setCancel(true) : setLeave(true))}
              >
                {owner ? "Hủy cuộc chơi nhóm" : "Rời nhóm"}
              </Button>
            )}
            {!mutable && pending && (
              <p className="small muted">
                Backend không cho đổi thành viên hoặc hủy nhóm khi đã bắt đầu
                thanh toán.
              </p>
            )}
          </Panel>
        </div>
      )}
      <Modal
        open={!!reminder}
        title="Xác nhận nhắc thanh toán"
        onClose={() => setReminder(null)}
      >
        <p className="small muted">
          Nhắc {recipients.map((m) => m.displayName).join(", ")} thanh toán phần
          còn thiếu trước {dateTime(g.deadline)}.
        </p>
        <DetailList
          items={recipients.map((m) => [
            m.displayName,
            money(Math.max(0, m.amountDue - m.amountPaid)),
          ])}
        />
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setReminder(null)}>
            Quay lại
          </Button>
          <Button
            busy={action.busy}
            onClick={() => {
              void action.run(
                () =>
                  api<{
                    acceptedCount: number;
                    skippedCount: number;
                    members: {
                      memberId: string;
                      reason: string | null;
                      outcome: string;
                    }[];
                  }>(`/groups/${g.id}/payment-reminders`, {
                    method: "POST",
                    body: JSON.stringify(
                      reminder === "all"
                        ? { remindAll: true }
                        : { memberIds: selected },
                    ),
                  }),
                (result) => {
                  setReminder(null);
                  setSelected([]);
                  reload();
                  action.setMessage(
                    `Đã xếp hàng ${result.acceptedCount} thông báo; bỏ qua ${result.skippedCount} thành viên không còn đủ điều kiện. Đây là xác nhận tiếp nhận, chưa phải xác nhận gửi thành công.`,
                  );
                },
              );
            }}
          >
            Xếp hàng thông báo
          </Button>
        </div>
      </Modal>
      <Modal
        open={cancel}
        title="Hủy cuộc chơi nhóm?"
        onClose={() => setCancel(false)}
      >
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void action.run(
              () =>
                api(`/groups/${g.id}/cancel`, {
                  method: "POST",
                  body: JSON.stringify({ reason: f.get("reason") }),
                }),
              () => {
                setCancel(false);
                reload();
              },
            );
          }}
        >
          <TextArea name="reason" label="Lý do hủy" required maxLength={500} />
          <Feedback action={action} />
          <div className="form-footer">
            <Button
              type="button"
              variant="secondary"
              onClick={() => setCancel(false)}
            >
              Giữ cuộc chơi
            </Button>
            <Button variant="danger" busy={action.busy}>
              Xác nhận hủy
            </Button>
          </div>
        </form>
      </Modal>
      <Modal
        open={leave}
        title="Rời nhóm chơi này?"
        onClose={() => setLeave(false)}
      >
        <p className="muted small">
          Bạn sẽ được gỡ khỏi danh sách tham gia. Chi phí chưa thanh toán sẽ
          được phân chia lại.
        </p>
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setLeave(false)}>
            Ở lại
          </Button>
          <Button
            variant="danger"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () => api(`/groups/${g.id}/leave`, { method: "POST" }),
                () => router.push("/customer/groups"),
              );
            }}
          >
            Rời nhóm
          </Button>
        </div>
      </Modal>
    </>
  );
}
export function JoinGroupPage() {
  const params = useSearchParams(),
    [code, setCode] = useState(params.get("code") ?? ""),
    [previewCode, setPreviewCode] = useState(params.get("code") ?? ""),
    r = useResource<Group>(
      previewCode
        ? "/groups/invitations?code=" + encodeURIComponent(previewCode)
        : null,
    ),
    action = useAction(),
    router = useRouter();
  return (
    <div className="container narrow">
      <PageHead
        eyebrow="Tham gia nhóm"
        title="Có đồng đội đang chờ bạn."
        description="Nhập mã mời hoặc mở liên kết từ người tổ chức để tham gia nhóm."
      />
      <Panel title="Lời mời tham gia">
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            setPreviewCode(code.trim());
          }}
        >
          <Field
            label="Mã mời nhóm"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            required
            maxLength={500}
          />
          <Button variant="secondary">Kiểm tra lời mời</Button>
        </form>
        {previewCode && (
          <ResourceState resource={r}>
            {r.data && (
              <div style={{ marginTop: 28 }}>
                <h2>{r.data.name}</h2>
                <DetailList
                  items={[
                    ["Thời gian", dateTime(r.data.booking.startsAt)],
                    [
                      "Thành viên",
                      `${r.data.members.filter((m) => m.active).length} / ${r.data.maxMembers}`,
                    ],
                    ["Tổng chi phí", money(r.data.booking.amount)],
                    ["Hạn đóng góp", dateTime(r.data.deadline)],
                  ]}
                />
                <Notice>
                  Chi phí cá nhân sẽ được xác định khi người tổ chức phân bổ cho
                  nhóm.
                </Notice>
                <Button
                  busy={action.busy}
                  onClick={() => {
                    void action.run(
                      () =>
                        api<Group>("/groups/join", {
                          method: "POST",
                          body: JSON.stringify({ code: previewCode }),
                        }),
                      (g) => router.push(`/customer/groups/${g.id}`),
                    );
                  }}
                >
                  Xác nhận tham gia <ArrowRight size={16} />
                </Button>
              </div>
            )}
          </ResourceState>
        )}
        <Feedback action={action} />
      </Panel>
    </div>
  );
}
