"use client";
import { Photo } from "@/rebuilt/components/photo";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { Plus, ShieldCheck, Wallet } from "lucide-react";
import { api, type Role } from "@/rebuilt/lib/api";
import { useAuth } from "@/rebuilt/lib/auth-context";
import { useAction, useIdempotency, useResource, formQuery } from "@/rebuilt/lib/hooks";
import {
  type Account,
  type Booking,
  type Category,
  type DocumentFile,
  type Facility,
  type FacilityDetail,
  type Media,
  type Payment,
  dateTime,
  money,
  shortId,
  label,
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
  Select,
  Table,
  Tabs,
  TextArea,
} from "@/rebuilt/components/ui";
import { Records } from "@/rebuilt/components/records";
const accountStates = [
  "PENDING_VERIFICATION",
  "ACTIVE",
  "LOCKED",
  "SUSPENDED",
  "DEACTIVATED",
];
const bookingStates = [
  "PENDING",
  "CONFIRMED",
  "CHECKED_IN",
  "COMPLETED",
  "CANCELLED",
  "EXPIRED",
  "GROUP_PENDING",
  "GROUP_EXPIRED",
  "GROUP_CANCELLED",
];
function StateFilter({
  states,
  name = "status",
  value,
}: {
  states: string[];
  name?: string;
  value: string;
}) {
  return (
    <Select name={name} label="Trạng thái" defaultValue={value}>
      <option value="">Tất cả trạng thái</option>
      {states.map((s) => (
        <option key={s} value={s}>
          {label(s)}
        </option>
      ))}
    </Select>
  );
}
export function AccountsPage() {
  const params = useSearchParams(),
    router = useRouter(),
    query = params.toString(),
    r = useResource<Account[]>("/admin/accounts?" + query);
  return (
    <div className="stack">
      <PageHead
        eyebrow="Quản lý tài khoản"
        title="Tài khoản nền tảng"
        description="Tra cứu người dùng, kiểm tra phiên và kiểm soát quyền truy cập."
      />
      <Panel>
        <form
          key={query}
          className="filter-row"
          onSubmit={(e) => {
            e.preventDefault();
            router.push("?" + formQuery(e.currentTarget));
          }}
        >
          <Field
            name="q"
            label="Tên, email hoặc số điện thoại"
            defaultValue={params.get("q") ?? ""}
          />
          <StateFilter
            states={accountStates}
            value={params.get("status") ?? ""}
          />
          <Select
            name="role"
            label="Vai trò"
            defaultValue={params.get("role") ?? ""}
          >
            <option value="">Tất cả vai trò</option>
            {["CUSTOMER", "OWNER", "STAFF", "ADMIN"].map((s) => (
              <option key={s} value={s}>
                {label(s)}
              </option>
            ))}
          </Select>
          <Button>Tìm tài khoản</Button>
        </form>
      </Panel>
      <Panel
        title="Danh sách tài khoản"
        description="API trả tối đa 100 tài khoản; thu hẹp bộ lọc khi cần."
      >
        <ResourceState resource={r} empty={r.data?.length === 0}>
          <Table
            headers={["Người dùng", "Liên hệ", "Vai trò", "Trạng thái", ""]}
          >
            {r.data?.map((a) => (
              <tr key={a.id}>
                <td>
                  <strong>{a.fullName}</strong>
                  <small>#{shortId(a.id)}</small>
                </td>
                <td>
                  {a.email || a.phone}
                  <small>{a.email && a.phone}</small>
                </td>
                <td>{a.roles.map(label).join(", ")}</td>
                <td>
                  <Badge state={a.status} />
                </td>
                <td>
                  <Link href={"/admin/accounts/" + a.id}>Xem chi tiết</Link>
                </td>
              </tr>
            ))}
          </Table>
        </ResourceState>
      </Panel>
    </div>
  );
}
type AccountDetail = {
  account: Account & { emailVerified: boolean; phoneVerified: boolean };
  sessions: unknown[];
  audit: unknown[];
};
export function AccountPage({ id }: { id: string }) {
  const r = useResource<AccountDetail>("/admin/accounts/" + id),
    { user } = useAuth(),
    action = useAction(),
    [mode, setMode] = useState(""),
    [tab, setTab] = useState("sessions");
  const a = r.data?.account,
    protectedAccount = id === user?.id || a?.roles.includes("ADMIN");
  return (
    <ResourceState resource={r}>
      {a && (
        <div className="stack">
          <PageHead
            eyebrow="Thông tin tài khoản"
            title={a.fullName}
            description={"Tài khoản #" + shortId(a.id)}
            action={<Badge state={a.status} />}
          />
          <Panel title="Thông tin & liên hệ">
            <DetailList
              items={[
                ["Email", a.email],
                [
                  "Xác minh email",
                  a.emailVerified ? "Đã xác minh" : "Chưa xác minh",
                ],
                ["Điện thoại", a.phone],
                [
                  "Xác minh điện thoại",
                  a.phoneVerified ? "Đã xác minh" : "Chưa xác minh",
                ],
                ["Vai trò", a.roles.map(label).join(", ")],
                ["Tham gia", dateTime(a.createdAt)],
                [
                  "Đăng nhập gần nhất",
                  a.lastLoginAt ? dateTime(a.lastLoginAt) : "Chưa ghi nhận",
                ],
                [
                  "Khóa đến",
                  a.lockedUntil
                    ? dateTime(a.lockedUntil)
                    : a.status === "LOCKED"
                      ? "Vĩnh viễn"
                      : "Không khóa",
                ],
                ["Lý do khóa", a.lockReason],
              ]}
            />
          </Panel>
          <Panel
            title="Kiểm soát truy cập"
            description="Thay đổi quyền hoặc khóa tài khoản sẽ thu hồi các phiên đang hoạt động."
          >
            {protectedAccount ? (
              <Notice>
                Backend không cho phép thay đổi tài khoản Admin hoặc tài khoản
                của chính người thao tác.
              </Notice>
            ) : (
              <div className="actions">
                <Button
                  variant={a.status === "LOCKED" ? "secondary" : "danger"}
                  onClick={() =>
                    setMode(a.status === "LOCKED" ? "unlock" : "lock")
                  }
                >
                  {a.status === "LOCKED"
                    ? "Mở khóa tài khoản"
                    : "Khóa tài khoản"}
                </Button>
                <Button
                  variant="secondary"
                  disabled={a.status === "PENDING_VERIFICATION"}
                  onClick={() => setMode("roles")}
                >
                  Cập nhật vai trò
                </Button>
              </div>
            )}
            <Feedback action={action} />
          </Panel>
          <Panel title="Lịch sử & phiên">
            <Tabs
              value={tab}
              onChange={setTab}
              items={[
                ["sessions", "Phiên đăng nhập"],
                ["audit", "Lịch sử quản trị"],
              ]}
            />
            <div style={{ marginTop: 22 }}>
              <Records
                value={tab === "sessions" ? r.data?.sessions : r.data?.audit}
              />
            </div>
          </Panel>
          <Modal
            open={!!mode}
            onClose={() => setMode("")}
            title={
              mode === "roles"
                ? "Cập nhật vai trò"
                : mode === "lock"
                  ? "Khóa tài khoản"
                  : "Mở khóa tài khoản"
            }
          >
            <form
              key={mode}
              onSubmit={(e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                void action.run(
                  () => {
                    const roles = f.getAll("roles") as Role[];
                    if (mode === "roles" && !roles.length)
                      throw new Error("Giữ ít nhất một vai trò.");
                    const body =
                      mode === "roles"
                        ? { roles, reason: f.get("reason") }
                        : mode === "lock"
                          ? {
                              reason: f.get("reason"),
                              duration: f.get("duration"),
                            }
                          : { reason: f.get("reason") };
                    return api(`/admin/accounts/${id}/${mode}`, {
                      method: mode === "roles" ? "PUT" : "POST",
                      body: JSON.stringify(body),
                    });
                  },
                  () => {
                    setMode("");
                    r.reload();
                    action.setMessage("Đã cập nhật tài khoản.");
                  },
                );
              }}
            >
              {mode === "lock" && (
                <Select name="duration" label="Thời hạn khóa">
                  <option value="SEVEN_DAYS">7 ngày</option>
                  <option value="THIRTY_DAYS">30 ngày</option>
                  <option value="PERMANENT">Vĩnh viễn</option>
                </Select>
              )}
              {mode === "roles" && (
                <>
                  <Notice tone="warning">
                    Vai trò quyết định quyền trên toàn nền tảng. Hồ sơ chủ sân
                    và quyền theo cơ sở được kiểm tra riêng.
                  </Notice>
                  <div className="actions" style={{ margin: "20px 0" }}>
                    {(["CUSTOMER", "OWNER", "STAFF", "ADMIN"] as Role[]).map(
                      (role) => (
                        <label key={role} className="small actions">
                          <input
                            type="checkbox"
                            name="roles"
                            value={role}
                            defaultChecked={a.roles.includes(role)}
                          />
                          {label(role)}
                        </label>
                      ),
                    )}
                  </div>
                </>
              )}
              <TextArea
                name="reason"
                label="Lý do thao tác"
                required
                maxLength={500}
              />
              <Feedback action={action} />
              <div className="form-footer">
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => setMode("")}
                >
                  Quay lại
                </Button>
                <Button
                  variant={mode === "lock" ? "danger" : "primary"}
                  busy={action.busy}
                >
                  Xác nhận thay đổi
                </Button>
              </div>
            </form>
          </Modal>
        </div>
      )}
    </ResourceState>
  );
}
export function AdminFacilities() {
  const params = useSearchParams(),
    query = params.toString(),
    router = useRouter(),
    r = useResource<Facility[]>("/admin/facilities?" + query);
  return (
    <div className="stack">
      <PageHead
        eyebrow="Quản lý cơ sở"
        title="Cơ sở thể thao"
        description="Kiểm tra hồ sơ cơ sở, lịch sử thẩm định và địa chỉ trùng."
      />
      <Panel>
        <form
          key={query}
          className="filter-row"
          onSubmit={(e) => {
            e.preventDefault();
            router.push("?" + formQuery(e.currentTarget));
          }}
        >
          <Field
            name="q"
            label="Tên, điện thoại hoặc địa chỉ"
            defaultValue={params.get("q") ?? ""}
          />
          <StateFilter
            states={["DRAFT", "PENDING_APPROVAL", "ACTIVE", "REJECTED"]}
            value={params.get("status") ?? ""}
          />
          <Button>Tìm cơ sở</Button>
        </form>
      </Panel>
      <Panel
        title="Danh sách cơ sở"
        description="Tối đa 100 kết quả theo bộ lọc."
      >
        <ResourceState resource={r} empty={r.data?.length === 0}>
          <Table headers={["Cơ sở", "Địa chỉ", "Trạng thái", ""]}>
            {r.data?.map((f) => (
              <tr key={f.id}>
                <td>
                  <strong>{f.name}</strong>
                  <small>{f.phone}</small>
                </td>
                <td>
                  {f.addressLine}
                  <small>
                    {f.district}, {f.province}
                  </small>
                </td>
                <td>
                  <Badge state={f.status} />
                </td>
                <td>
                  <Link href={"/admin/facilities/" + f.id}>
                    Thẩm định hồ sơ
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        </ResourceState>
      </Panel>
    </div>
  );
}
export function AdminFacility({ id }: { id: string }) {
  const r = useResource<FacilityDetail & { possibleDuplicates: Facility[] }>(
      `/admin/facilities/${id}`,
    ),
    docs = useResource<DocumentFile[]>(`/admin/facilities/${id}/documents`),
    images = useResource<Media[]>(`/admin/facilities/${id}/images`),
    [tab, setTab] = useState("profile"),
    [decision, setDecision] = useState<Record<string, string> | null>(null),
    action = useAction();
  const data = r.data;
  return (
    <ResourceState resource={r}>
      {data && (
        <div className="stack">
          <PageHead
            eyebrow="Kiểm duyệt cơ sở"
            title={data.facility.name}
            description={data.facility.addressLine}
            action={<Badge state={data.facility.status} />}
          />
          {data.possibleDuplicates.length > 0 && (
            <Notice tone="warning">
              Có {data.possibleDuplicates.length} cơ sở cùng địa chỉ.{" "}
              {data.possibleDuplicates.map((f) => (
                <Link
                  key={f.id}
                  href={"/admin/facilities/" + f.id}
                  style={{ marginRight: 12 }}
                >
                  {f.name}
                </Link>
              ))}
            </Notice>
          )}
          <Tabs
            value={tab}
            onChange={setTab}
            items={[
              ["profile", "Thông tin & sân"],
              ["files", "Ảnh & tài liệu"],
              ["reviews", "Lần thẩm định"],
              ["audit", "Lịch sử quản trị"],
            ]}
          />
          <Panel>
            {tab === "profile" ? (
              <Records
                value={{ facility: data.facility, courts: data.courts }}
              />
            ) : tab === "files" ? (
              <div className="stack">
                <ResourceState resource={images}>
                  <div className="grid-3">
                    {images.data?.map((m) => (
                      <a
                        href={m.url}
                        target="_blank"
                        rel="noreferrer"
                        key={m.id}
                      >
                        <Photo
                          src={m.url}
                          alt="Ảnh hồ sơ cơ sở"
                          style={{
                            width: "100%",
                            height: 180,
                            objectFit: "cover",
                            borderRadius: 10,
                          }}
                        />
                      </a>
                    ))}
                  </div>
                </ResourceState>
                <ResourceState resource={docs} empty={docs.data?.length === 0}>
                  <Table headers={["Tài liệu", "Dung lượng"]}>
                    {docs.data?.map((d) => (
                      <tr key={d.id}>
                        <td>
                          <a href={d.url} target="_blank" rel="noreferrer">
                            {d.name}
                          </a>
                          {d.archived && <small>Tài liệu đã lưu trữ</small>}
                        </td>
                        <td>{(d.sizeBytes / 1024).toFixed(1)} KB</td>
                      </tr>
                    ))}
                  </Table>
                </ResourceState>
              </div>
            ) : (
              <Records value={tab === "reviews" ? data.reviews : data.audit} />
            )}
          </Panel>
          {data.facility.status === "PENDING_APPROVAL" && (
            <Panel
              title="Quyết định thẩm định"
              description="Cơ sở đầu tiên gắn với hồ sơ chủ sân cần được quyết định tại mục Hồ sơ chủ sân."
            >
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const f = new FormData(e.currentTarget),
                    body = {
                      action: String(f.get("action")),
                      reason: String(f.get("reason")).trim(),
                    };
                  if (body.action !== "APPROVE" && body.reason.length < 20) {
                    action.setError(
                      "Lý do từ chối hoặc bổ sung cần ít nhất 20 ký tự.",
                    );
                    return;
                  }
                  setDecision(body);
                }}
              >
                <div className="form-grid">
                  <Select name="action" label="Kết quả">
                    <option value="APPROVE">Phê duyệt cơ sở</option>
                    <option value="SUPPLEMENT_REQUIRED">Yêu cầu bổ sung</option>
                    <option value="REJECT">Từ chối</option>
                  </Select>
                  <TextArea
                    name="reason"
                    label="Nhận xét thẩm định"
                    required
                    maxLength={2000}
                  />
                </div>
                <div className="form-footer">
                  <Link
                    href="/admin/owner-applications"
                    className="text-link small"
                  >
                    Tra cứu hồ sơ chủ sân
                  </Link>
                  <Button>Xem lại quyết định</Button>
                </div>
              </form>
              <Feedback action={action} />
            </Panel>
          )}
          <Modal
            open={!!decision}
            title="Xác nhận kết quả thẩm định"
            onClose={() => setDecision(null)}
          >
            <p>
              {decision?.action === "APPROVE"
                ? "Đưa cơ sở vào hoạt động."
                : decision?.action === "REJECT"
                  ? "Từ chối hồ sơ cơ sở."
                  : "Trả cơ sở về bản nháp để bổ sung."}
            </p>
            <p className="small muted" style={{ margin: "16px 0" }}>
              {decision?.reason}
            </p>
            <Feedback action={action} />
            <div className="form-footer">
              <Button variant="secondary" onClick={() => setDecision(null)}>
                Quay lại
              </Button>
              <Button
                busy={action.busy}
                onClick={() => {
                  void action.run(
                    () =>
                      api(`/admin/facilities/${id}/decision`, {
                        method: "POST",
                        body: JSON.stringify(decision),
                      }),
                    () => {
                      setDecision(null);
                      r.reload();
                    },
                  );
                }}
              >
                Lưu quyết định
              </Button>
            </div>
          </Modal>
        </div>
      )}
    </ResourceState>
  );
}
export function AdminBookings({
  reconciliation = false,
}: {
  reconciliation?: boolean;
}) {
  const params = useSearchParams(),
    query = params.toString(),
    router = useRouter(),
    r = useResource<Booking[]>("/admin/bookings?" + query),
    audit = useResource<unknown[]>(
      reconciliation ? "/admin/bookings/reconciliation" : null,
    );
  if (reconciliation)
    return (
      <>
        <PageHead
          eyebrow="Đối soát"
          title="Đối soát booking"
          description="Các bản ghi lệch trạng thái do backend ghi nhận; tối đa 200 bản ghi gần nhất."
        />
        <Panel>
          <ResourceState
            resource={audit}
            empty={audit.data?.length === 0}
            emptyTitle="Chưa có lệch cần đối soát"
            emptyDescription="Backend chưa ghi nhận trường hợp bất nhất thanh toán và booking."
          >
            <Records value={audit.data} />
          </ResourceState>
        </Panel>
      </>
    );
  return (
    <div className="stack">
      <PageHead
        eyebrow="Theo dõi lịch đặt"
        title="Giám sát booking"
        description="Theo dõi trạng thái và nhật ký của lượt đặt trên nền tảng."
      />
      <Panel>
        <form
          key={query}
          className="filter-row"
          onSubmit={(e) => {
            e.preventDefault();
            router.push("?" + formQuery(e.currentTarget));
          }}
        >
          <Field
            name="id"
            label="Mã booking (UUID)"
            defaultValue={params.get("id") ?? ""}
          />
          <Field
            name="facilityId"
            label="Mã cơ sở (UUID)"
            defaultValue={params.get("facilityId") ?? ""}
          />
          <StateFilter
            states={bookingStates}
            value={params.get("status") ?? ""}
          />
          <Button>Áp dụng bộ lọc</Button>
        </form>
      </Panel>
      <Panel
        title="Lịch đặt gần nhất"
        description="Tối đa 200 booking theo bộ lọc."
      >
        <ResourceState resource={r} empty={r.data?.length === 0}>
          <Table
            headers={[
              "Lịch đặt",
              "Lịch chơi",
              "Nguồn",
              "Giá trị",
              "Trạng thái",
              "",
            ]}
          >
            {r.data?.map((b) => (
              <tr key={b.id}>
                <td>
                  <strong>#{shortId(b.id)}</strong>
                  <small>Sân #{shortId(b.courtId)}</small>
                </td>
                <td>
                  {dateTime(b.startsAt, b.priceSnapshot.timezone)}
                  <small>
                    đến {dateTime(b.endsAt, b.priceSnapshot.timezone)}
                  </small>
                </td>
                <td>
                  {b.source === "OFFLINE_COUNTER" ? "Tại quầy" : "Trực tuyến"}
                </td>
                <td>{money(b.amount, b.currency)}</td>
                <td>
                  <Badge state={b.status} />
                </td>
                <td>
                  <Link href={"/admin/bookings/" + b.id}>Chi tiết</Link>
                </td>
              </tr>
            ))}
          </Table>
        </ResourceState>
      </Panel>
    </div>
  );
}
export function AdminPayments() {
  const params = useSearchParams(),
    query = params.toString(),
    router = useRouter(),
    r = useResource<Payment[]>("/admin/payments?" + query);
  return (
    <div className="stack">
      <PageHead
        eyebrow="Theo dõi thanh toán"
        title="Giao dịch thanh toán"
        description="Tra cứu đơn thanh toán, callback, escrow và yêu cầu hoàn tiền."
      />
      <Panel>
        <form
          key={query}
          className="filter-row"
          onSubmit={(e) => {
            e.preventDefault();
            router.push("?" + formQuery(e.currentTarget));
          }}
        >
          <Field
            name="q"
            label="Mã thanh toán / booking / tham chiếu"
            defaultValue={params.get("q") ?? ""}
          />
          <StateFilter
            states={["PENDING", "SUCCESS", "FAILED", "EXPIRED"]}
            value={params.get("status") ?? ""}
          />
          <Select
            name="purpose"
            label="Mục đích"
            defaultValue={params.get("purpose") ?? ""}
          >
            <option value="">Tất cả mục đích</option>
            <option value="BOOKING">Đặt sân</option>
            <option value="GROUP_CONTRIBUTION">Đóng góp nhóm</option>
            <option value="TRANSFER">Nhận chuyển nhượng</option>
          </Select>
          <Button>Tìm giao dịch</Button>
        </form>
      </Panel>
      <Panel
        title="Đơn thanh toán gần nhất"
        description="Tối đa 200 kết quả. Tổng tiền đơn không đại diện cho tiền chủ sân đã được chi trả."
      >
        <ResourceState resource={r} empty={r.data?.length === 0}>
          <Table
            headers={[
              "Thanh toán",
              "Mục đích",
              "Số tiền",
              "Provider",
              "Trạng thái",
              "",
            ]}
          >
            {r.data?.map((p) => (
              <tr key={p.id}>
                <td>
                  <strong>#{shortId(p.id)}</strong>
                  <small>Lịch đặt #{shortId(p.bookingId)}</small>
                </td>
                <td>{label(p.purpose)}</td>
                <td>{money(p.amount, p.currency)}</td>
                <td>{p.provider}</td>
                <td>
                  <Badge state={p.status} />
                </td>
                <td>
                  <Link href={"/admin/payments/" + p.id}>
                    Đối soát chi tiết
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        </ResourceState>
      </Panel>
    </div>
  );
}
type PaymentDetail = {
  payment: Payment;
  audit: unknown[];
  callbacks: unknown[];
  requests: unknown[];
  refunds: unknown[];
  escrow: unknown[];
};
export function AdminPayment({ id }: { id: string }) {
  const r = useResource<PaymentDetail>("/admin/payments/" + id),
    [tab, setTab] = useState("audit"),
    [refund, setRefund] = useState(false),
    action = useAction(),
    idempotency = useIdempotency(),
    p = r.data?.payment;
  return (
    <ResourceState resource={r}>
      {p && (
        <div className="stack">
          <PageHead
            eyebrow="Chi tiết thanh toán"
            title={"Giao dịch #" + shortId(id)}
            description={p.providerReference}
            action={<Badge state={p.status} />}
          />
          <div className="grid-2">
            <Panel title="Thông tin thanh toán">
              <DetailList
                items={[
                  [
                    "Lịch đặt",
                    <Link key="booking" href={"/admin/bookings/" + p.bookingId}>
                      #{shortId(p.bookingId)}
                    </Link>,
                  ],
                  ["Số tiền", money(p.amount, p.currency)],
                  ["Mục đích", label(p.purpose)],
                  ["Provider", p.provider],
                  ["Thời hạn", dateTime(p.expiresAt)],
                  [
                    "Thanh toán lúc",
                    p.paidAt ? dateTime(p.paidAt) : "Chưa thanh toán",
                  ],
                ]}
              />
            </Panel>
            <Panel title="Hoàn tiền & chi trả">
              <Notice tone="warning">
                Backend hiện chỉ ghi nhận yêu cầu hoàn tiền để rà soát
                (BLOCKED_RULE). Tiền chuyển nhượng được giữ trong escrow; chưa
                có API chi trả người bán.
              </Notice>
              {p.status === "SUCCESS" && (
                <Button
                  variant="secondary"
                  style={{ marginTop: 20 }}
                  onClick={() => setRefund(true)}
                >
                  <Wallet size={17} />
                  Ghi nhận yêu cầu rà soát
                </Button>
              )}
              <Feedback action={action} />
            </Panel>
          </div>
          <Panel title="Bằng chứng giao dịch">
            <Tabs
              value={tab}
              onChange={setTab}
              items={[
                ["audit", "Nhật ký"],
                ["callbacks", "Callback"],
                ["requests", "Yêu cầu tạo đơn"],
                ["refunds", "Rà soát hoàn tiền"],
                ["escrow", "Escrow"],
              ]}
            />
            <div style={{ marginTop: 24 }}>
              <Records
                value={r.data?.[tab as keyof Omit<PaymentDetail, "payment">]}
              />
            </div>
          </Panel>
          <Modal
            open={refund}
            title="Ghi nhận yêu cầu rà soát hoàn tiền"
            onClose={() => setRefund(false)}
          >
            <Notice tone="warning">
              Thao tác tạo bản ghi rà soát. Không thực hiện hoàn tiền hay xác
              định số tiền hoàn.
            </Notice>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const reason = String(
                  new FormData(e.currentTarget).get("reason"),
                ).trim();
                void action.run(
                  () =>
                    api<{ state: string }>(`/payments/${id}/refunds`, {
                      method: "POST",
                      headers: {
                        "Idempotency-Key": idempotency("refund:" + id + reason),
                      },
                      body: JSON.stringify({ reason }),
                    }),
                  (result) => {
                    setRefund(false);
                    r.reload();
                    action.setMessage(
                      `Đã ghi nhận yêu cầu. Trạng thái: ${label(result.state)}.`,
                    );
                  },
                );
              }}
            >
              <TextArea
                name="reason"
                label="Lý do cần rà soát"
                required
                maxLength={500}
              />
              <Feedback action={action} />
              <div className="form-footer">
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => setRefund(false)}
                >
                  Quay lại
                </Button>
                <Button busy={action.busy}>Ghi nhận yêu cầu</Button>
              </div>
            </form>
          </Modal>
        </div>
      )}
    </ResourceState>
  );
}
export function SportsPage() {
  const r = useResource<Category[]>("/sport-categories"),
    action = useAction();
  return (
    <div className="stack">
      <PageHead
        eyebrow="Bộ môn thể thao"
        title="Danh mục thể thao"
        description="Các bộ môn dùng chung cho tìm kiếm, sân và bảng giá."
      />
      <div className="grid-2">
        <Panel title="Thêm môn thể thao">
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const form = e.currentTarget,
                f = new FormData(form);
              void action.run(
                () =>
                  api("/admin/sport-categories", {
                    method: "POST",
                    body: JSON.stringify({
                      name: String(f.get("name")).trim(),
                      active: f.get("active") === "on",
                    }),
                  }),
                () => {
                  form.reset();
                  r.reload();
                  action.setMessage("Đã tạo môn thể thao.");
                },
              );
            }}
          >
            <Field
              name="name"
              label="Tên môn thể thao"
              required
              maxLength={100}
              error={action.fields.name}
            />
            <label className="actions small" style={{ margin: "20px 0" }}>
              <input type="checkbox" name="active" defaultChecked />
              Cho phép sử dụng ngay
            </label>
            <Feedback action={action} />
            <Button busy={action.busy}>
              <Plus size={17} />
              Thêm bộ môn
            </Button>
          </form>
        </Panel>
        <Panel title="Bộ môn hiện có">
          <ResourceState resource={r} empty={r.data?.length === 0}>
            <Table headers={["Bộ môn", "Trạng thái"]}>
              {r.data?.map((c) => (
                <tr key={c.id}>
                  <td>{c.name}</td>
                  <td>
                    <Badge state={c.active ? "ACTIVE" : "DISABLED"} />
                  </td>
                </tr>
              ))}
            </Table>
          </ResourceState>
          <p className="muted small" style={{ marginTop: 20 }}>
            API hiện chưa hỗ trợ đổi tên hoặc ngừng sử dụng bộ môn đã tạo.
          </p>
        </Panel>
      </div>
    </div>
  );
}
export function DisputesPage() {
  return (
    <div className="stack">
      <PageHead
        eyebrow="Hỗ trợ khách hàng"
        title="Khiếu nại & tranh chấp"
        description="Chức năng mục tiêu theo tài liệu nghiệp vụ."
      />
      <Panel title="Chưa có dịch vụ tiếp nhận khiếu nại">
        <div className="feature-icon">
          <ShieldCheck size={30} />
        </div>
        <p className="muted" style={{ margin: "20px 0" }}>
          Repository hiện chưa có API hoặc bảng dữ liệu cho hồ sơ khiếu nại,
          phân công xử lý và quyết định tranh chấp. Chức năng này cần được triển
          khai ở backend trước khi có thể sử dụng.
        </p>
        <div className="actions">
          <LinkButton href="/admin/bookings" variant="secondary">
            Tra cứu booking
          </LinkButton>
          <LinkButton href="/admin/payments" variant="secondary">
            Tra cứu thanh toán
          </LinkButton>
          <LinkButton href="/admin/bookings/reconciliation" variant="secondary">
            Xem đối soát
          </LinkButton>
        </div>
      </Panel>
    </div>
  );
}
