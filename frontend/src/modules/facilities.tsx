"use client";
import { Photo } from "@/rebuilt/components/photo";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { createContext, useContext, useState, type ReactNode } from "react";
import {
  Activity,
  ArrowUpRight,
  Building2,
  ImagePlus,
  Plus,
} from "lucide-react";
import { api } from "@/rebuilt/lib/api";
import { useAction, useResource } from "@/rebuilt/lib/hooks";
import {
  type Binding,
  type Category,
  type Court,
  type DocumentFile,
  type Facility,
  type FacilityReview,
  type Maintenance,
  type Media,
  dateTime,
  localInstant,
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
  Select,
  Stat,
  Table,
  TextArea,
} from "@/rebuilt/components/ui";
import {
  FacilityFields,
  UploadField,
  facilityFrom,
  validateFile,
} from "@/rebuilt/components/domain-forms";
type FacilityScope = {
  facility: Facility;
  reload: () => void;
  base: string;
  editable: boolean;
};
const Context = createContext<FacilityScope | null>(null);
export function useFacility() {
  const value = useContext(Context);
  if (!value) throw new Error("Missing facility workspace");
  return value;
}
export function FacilityWorkspace({
  id,
  applicationId,
  children,
}: {
  id: string;
  applicationId?: string;
  children: ReactNode;
}) {
  const r = useResource<Facility>(`/owner/facilities/${id}`),
    path = usePathname(),
    base = applicationId
      ? `/owner/application/${applicationId}/facility`
      : `/owner/facilities/${id}`;
  const router = useRouter();
  const editable = !!r.data && ["DRAFT", "ACTIVE"].includes(r.data.status);
  const groups = applicationId
    ? [
        { label: "Cơ sở", links: [["", "Tổng quan"], ["/edit", "Thông tin"], ["/courts", "Sân"]] },
        { label: "Lịch và giá", links: [["/schedule", "Lịch mở cửa"], ["/pricing", "Bảng giá"]] },
        { label: "Hồ sơ", links: [["/documents", "Tài liệu"]] },
      ]
    : [
        { label: "Cơ sở", links: [["", "Tổng quan"], ["/courts", "Sân"], ["/staff", "Nhân viên"], ["/edit", "Thông tin"]] },
        { label: "Lịch và giá", links: [["/schedule", "Lịch mở cửa"], ["/pricing", "Bảng giá"], ["/maintenance", "Bảo trì"], ["/transfer-policy", "Chuyển nhượng"]] },
        { label: "Hồ sơ và báo cáo", links: [["/documents", "Tài liệu"], ["/review", "Kiểm duyệt"], ["/reports", "Báo cáo"]] },
      ];
  const activeLink = groups.flatMap((group) => group.links)
    .filter(([suffix]) => path === base + suffix || (suffix && path.startsWith(base + suffix + "/")))
    .sort((a, b) => b[0].length - a[0].length)[0];
  return (
    <ResourceState resource={r}>
      {r.data && (
        <Context.Provider
          value={{ facility: r.data, reload: r.reload, base, editable }}
        >
          <PageHead
            eyebrow={
              applicationId ? "Thiết lập cơ sở" : "Quản lý cơ sở"
            }
            title={r.data.name}
            description={`${r.data.addressLine} · ${r.data.timezone}`}
            action={<Badge state={r.data.status} />}
          />
          <nav className="facility-nav" aria-label="Chức năng cơ sở">
            <div className="facility-nav-groups">
              {groups.map((group) => (
                <div className="facility-nav-group" key={group.label}>
                  <span>{group.label}</span>
                  <div>
                    {group.links.map(([suffix, text]) => (
                      <Link
                        className={activeLink?.[0] === suffix ? "active" : ""}
                        aria-current={activeLink?.[0] === suffix ? "page" : undefined}
                        key={suffix}
                        href={base + suffix}
                      >{text}</Link>
                    ))}
                  </div>
                </div>
              ))}
            </div>
            <select className="facility-nav-select" aria-label="Chọn mục quản lý cơ sở"
              value={base + (activeLink?.[0] ?? "")} onChange={(event) => router.push(event.target.value)}>
              {groups.map((group) => <optgroup label={group.label} key={group.label}>
                {group.links.map(([suffix, text]) => <option value={base + suffix} key={suffix}>{text}</option>)}
              </optgroup>)}
            </select>
          </nav>
          {!editable && (
            <Notice tone="warning">
              Cơ sở đang ở trạng thái{" "}
              {r.data.status === "PENDING_APPROVAL"
                ? "chờ thẩm định"
                : "không được chỉnh sửa"}
              . Hồ sơ đã gửi được giữ nguyên để kiểm duyệt.
            </Notice>
          )}
          {children}
        </Context.Provider>
      )}
    </ResourceState>
  );
}
export function FacilitiesPage() {
  const r = useResource<Facility[]>("/owner/facilities");
  return (
    <>
      <PageHead
        eyebrow="Cơ sở thể thao"
        title="Cơ sở của bạn"
        description="Mỗi không gian, một trải nghiệm thể thao riêng."
        action={
          <LinkButton href="/owner/facilities/new">
            <Plus size={16} />
            Tạo cơ sở
          </LinkButton>
        }
      />
      <ResourceState
        resource={r}
        empty={r.data?.length === 0}
        emptyTitle="Chưa có cơ sở"
        emptyDescription="Tạo hồ sơ cơ sở để thiết lập sân, giờ hoạt động và gửi kiểm duyệt."
      >
        <div className="grid-3">
          {r.data?.map((f) => (
            <Panel key={f.id}>
              <div
                className="actions"
                style={{ justifyContent: "space-between", marginBottom: 22 }}
              >
                <span className="sport-icon">
                  <Building2 size={23} />
                </span>
                <Badge state={f.status} />
              </div>
              <h3>{f.name}</h3>
              <p className="muted small" style={{ margin: "12px 0 24px" }}>
                {f.addressLine}, {f.district}, {f.province}
              </p>
              <LinkButton
                href={`/owner/facilities/${f.id}`}
                variant="secondary"
                arrow
              >
                Quản lý cơ sở
              </LinkButton>
            </Panel>
          ))}
        </div>
      </ResourceState>
    </>
  );
}
export function FacilityOverview() {
  const { facility: f, base, editable } = useFacility(),
    courts = useResource<Court[]>(`/owner/facilities/${f.id}/courts`),
    images = useResource<Media[]>(`/owner/facilities/${f.id}/images`),
    action = useAction(),
    [remove, setRemove] = useState<string | null>(null);
  return (
    <div className="stack">
      <div className="grid-3">
        <Stat
          label="Sân đang hoạt động"
          value={
            courts.data ? courts.data.filter((c) => c.enabled).length : "—"
          }
          icon={<Activity size={20} />}
        />
        <Stat
          label="Tổng số sân"
          value={courts.data?.length ?? "—"}
          icon={<Building2 size={20} />}
        />
        <Stat
          label="Ảnh cơ sở"
          value={images.data?.length ?? "—"}
          icon={<ImagePlus size={20} />}
        />
      </div>
      <div className="grid-2">
        <Panel
          title="Hồ sơ cơ sở"
          action={
            <LinkButton href={base + "/edit"} variant="secondary">
              Chỉnh sửa
            </LinkButton>
          }
        >
          <DetailList
            items={[
              [
                "Địa chỉ",
                `${f.addressLine}, ${f.ward}, ${f.district}, ${f.province}`,
              ],
              ["Số điện thoại", f.phone],
              ["Email", f.contactEmail || "Chưa cung cấp"],
              ["Múi giờ", f.timezone],
              ["Tiện ích", f.amenities.join(", ") || "Chưa khai báo"],
            ]}
          />
          <p className="small muted" style={{ marginTop: 20 }}>
            {f.description}
          </p>
        </Panel>
        <Panel title="Ảnh & không gian">
          <ResourceState resource={images}>
            {images.data?.length ? (
              <div className="grid-2" style={{ gap: 12 }}>
                {images.data.map((m) => (
                  <div key={m.id}>
                    <Photo
                      src={m.url}
                      alt="Ảnh cơ sở"
                      style={{
                        height: 130,
                        width: "100%",
                        objectFit: "cover",
                        borderRadius: 8,
                      }}
                    />
                    {editable && (
                      <Button variant="ghost" onClick={() => setRemove(m.id)}>
                        Gỡ ảnh
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <div className="gallery-empty">
                <ImagePlus size={30} />
                <p className="small">Thêm ảnh để người chơi khám phá cơ sở.</p>
              </div>
            )}
          </ResourceState>
          {editable && (
            <form
              style={{ marginTop: 20 }}
              onSubmit={(e) => {
                e.preventDefault();
                const body = new FormData(e.currentTarget);
                const form = e.currentTarget;
                void action.run(
                  () => {
                    validateFile(body.get("file"), true);
                    if (!body.get("courtId")) body.delete("courtId");
                    return api(`/owner/facilities/${f.id}/images`, {
                      method: "POST",
                      body,
                    });
                  },
                  () => {
                    images.reload();
                    form.reset();
                    action.setMessage("Đã tải ảnh lên cơ sở.");
                  },
                );
              }}
            >
              <UploadField label="Thêm ảnh giới thiệu" image />
              <Select name="courtId" label="Ảnh thuộc sân (tùy chọn)">
                <option value="">Toàn cơ sở</option>
                {courts.data?.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </Select>
              <Button busy={action.busy} style={{ marginTop: 15 }}>
                Tải ảnh lên
              </Button>
            </form>
          )}
          <Feedback action={action} />
        </Panel>
      </div>
      <Modal
        open={!!remove}
        title="Gỡ ảnh này?"
        onClose={() => setRemove(null)}
      >
        <p className="muted small">
          Ảnh sẽ không còn hiển thị trên hồ sơ cơ sở.
        </p>
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setRemove(null)}>
            Giữ ảnh
          </Button>
          <Button
            variant="danger"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () =>
                  api(`/owner/facilities/${f.id}/images/${remove}`, {
                    method: "DELETE",
                  }),
                () => {
                  setRemove(null);
                  images.reload();
                },
              );
            }}
          >
            Gỡ ảnh
          </Button>
        </div>
      </Modal>
    </div>
  );
}
export function FacilityForm({ create = false }: { create?: boolean }) {
  const scope = useContext(Context),
    action = useAction(),
    router = useRouter();
  return (
    <Panel
      title={
        create ? "Tạo một không gian thể thao mới" : "Thông tin & cài đặt cơ sở"
      }
      description="Thông tin này giúp người chơi tìm và lựa chọn sân của bạn."
    >
      <form
        key={scope?.facility.version ?? "new"}
        onSubmit={(e) => {
          e.preventDefault();
          const f = new FormData(e.currentTarget);
          void action.run(
            () =>
              api<Facility>(
                create
                  ? "/owner/facilities"
                  : `/owner/facilities/${scope!.facility.id}`,
                {
                  method: create ? "POST" : "PUT",
                  body: JSON.stringify(facilityFrom(f)),
                },
              ),
            (saved) => {
              if (create) router.push(`/owner/facilities/${saved.id}`);
              else {
                scope?.reload();
                action.setMessage("Thông tin cơ sở đã được cập nhật.");
              }
            },
          );
        }}
      >
        <fieldset disabled={action.busy || (!create && !scope?.editable)}>
          <div className="form-grid">
            <FacilityFields value={scope?.facility} errors={action.fields} />
          </div>
        </fieldset>
        <Feedback action={action} />
        <div className="form-footer">
          <p className="small muted">
            Ảnh, sân và lịch được quản lý trong các mục riêng.
          </p>
          <Button busy={action.busy} disabled={!create && !scope?.editable}>
            Lưu hồ sơ cơ sở
          </Button>
        </div>
      </form>
    </Panel>
  );
}
export function CourtsPage() {
  const { facility: f, base, editable } = useFacility(),
    r = useResource<Court[]>(`/owner/facilities/${f.id}/courts`),
    categories = useResource<Category[]>("/sport-categories");
  return (
    <Panel
      title="Danh sách sân"
      description="Trạng thái tạm ngừng chặn booking mới; booking cũ vẫn được giữ."
      action={
        editable ? (
          <LinkButton href={base + "/courts/new"}>
            <Plus size={16} />
            Thêm sân
          </LinkButton>
        ) : undefined
      }
    >
      <ResourceState
        resource={r}
        empty={r.data?.length === 0}
        emptyTitle="Chưa có sân"
        emptyDescription="Thêm sân đầu tiên rồi cấu hình giờ hoạt động và bảng giá."
      >
        <Table headers={["Sân / Mã sân", "Bộ môn", "Trạng thái", "Thao tác"]}>
          {r.data?.map((c) => (
            <tr key={c.id}>
              <td>
                <strong>{c.name}</strong>
                <small>{c.code}</small>
              </td>
              <td>
                {categories.data?.find((x) => x.id === c.sportCategoryId)
                  ?.name ?? shortId(c.sportCategoryId)}
              </td>
              <td>
                <Badge state={c.enabled ? "ACTIVE" : "DISABLED"} />
              </td>
              <td>
                <Link href={`${base}/courts/${c.id}/edit`}>
                  Xem & chỉnh sửa
                </Link>
              </td>
            </tr>
          ))}
        </Table>
      </ResourceState>
    </Panel>
  );
}
export function CourtForm({ id }: { id?: string }) {
  const { facility: f, base, editable } = useFacility(),
    r = useResource<Court>(id ? `/owner/courts/${id}` : null),
    categories = useResource<Category[]>("/sport-categories"),
    action = useAction(),
    router = useRouter();
  const content = (
    <Panel title={id ? "Thông tin & trạng thái sân" : "Thêm sân mới"}>
      <form
        key={r.data?.version ?? "new"}
        onSubmit={(e) => {
          e.preventDefault();
          const fdata = new FormData(e.currentTarget);
          const body = {
            name: String(fdata.get("name")),
            code: String(fdata.get("code")),
            sportCategoryId: String(fdata.get("sportCategoryId")),
            description: String(fdata.get("description")),
            enabled: fdata.get("enabled") === "on",
          };
          void action.run(
            () =>
              api(
                id ? `/owner/courts/${id}` : `/owner/facilities/${f.id}/courts`,
                { method: id ? "PUT" : "POST", body: JSON.stringify(body) },
              ),
            () => router.push(base + "/courts"),
          );
        }}
      >
        <fieldset disabled={action.busy || !editable}>
          <div className="form-grid">
            <Field
              name="name"
              label="Tên sân"
              required
              maxLength={180}
              defaultValue={r.data?.name}
              error={action.fields.name}
            />
            <Field
              name="code"
              label="Mã sân"
              required
              maxLength={50}
              defaultValue={r.data?.code}
              error={action.fields.code}
            />
            <Select
              name="sportCategoryId"
              label="Bộ môn"
              required
              defaultValue={r.data?.sportCategoryId ?? ""}
            >
              <option value="">Chọn bộ môn</option>
              {categories.data
                ?.filter((c) => c.active || c.id === r.data?.sportCategoryId)
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                    {!c.active ? " (ngừng sử dụng)" : ""}
                  </option>
                ))}
            </Select>
            <TextArea
              name="description"
              label="Mô tả sân"
              defaultValue={r.data?.description ?? ""}
            />
            <label className="small actions">
              <input
                type="checkbox"
                name="enabled"
                defaultChecked={r.data?.enabled ?? true}
              />
              Mở nhận booking mới
            </label>
          </div>
        </fieldset>
        <Feedback action={action} />
        <div className="form-footer">
          <Link className="text-link small" href={base + "/courts"}>
            Quay lại danh sách sân
          </Link>
          <Button busy={action.busy} disabled={!editable}>
            Lưu sân
          </Button>
        </div>
      </form>
    </Panel>
  );
  return id ? (
    <ResourceState resource={r}>
      {r.data && r.data.facilityId !== f.id ? (
        <Notice tone="error">Sân này không thuộc cơ sở đang quản lý.</Notice>
      ) : (
        content
      )}
    </ResourceState>
  ) : (
    content
  );
}
export function MaintenancePage() {
  const { facility: f, editable } = useFacility(),
    courts = useResource<Court[]>(`/owner/facilities/${f.id}/courts`),
    [choice, setChoice] = useState(""),
    court = choice || courts.data?.[0]?.id,
    r = useResource<Maintenance[]>(
      court ? `/owner/courts/${court}/maintenance` : null,
    ),
    action = useAction(),
    [remove, setRemove] = useState<string | null>(null);
  return (
    <div className="stack">
      <Panel title="Bảo trì & tạm ngừng phục vụ">
        <Select
          label="Chọn sân"
          value={court ?? ""}
          onChange={(e) => setChoice(e.target.value)}
        >
          <option value="" disabled>
            Chọn sân
          </option>
          {courts.data?.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </Select>
        {courts.error && <Notice tone="error">{courts.error}</Notice>}
        {editable && court && (
          <form
            style={{ marginTop: 24 }}
            onSubmit={(e) => {
              e.preventDefault();
              const values = new FormData(e.currentTarget);
              const form = e.currentTarget;
              void action.run(
                () => {
                  const startsAt = localInstant(String(values.get("startsAt"))),
                    endsAt = localInstant(String(values.get("endsAt")));
                  if (startsAt >= endsAt)
                    throw new Error(
                      "Thời gian kết thúc phải sau thời gian bắt đầu.",
                    );
                  return api(`/owner/courts/${court}/maintenance`, {
                    method: "POST",
                    body: JSON.stringify({
                      startsAt,
                      endsAt,
                      reason: values.get("reason"),
                    }),
                  });
                },
                () => {
                  r.reload();
                  form.reset();
                  action.setMessage("Đã thêm lịch bảo trì.");
                },
              );
            }}
          >
            <fieldset disabled={action.busy}>
              <div className="form-grid">
                <Field
                  name="startsAt"
                  label="Bắt đầu (giờ Việt Nam)"
                  type="datetime-local"
                  required
                />
                <Field
                  name="endsAt"
                  label="Kết thúc (giờ Việt Nam)"
                  type="datetime-local"
                  required
                />
                <TextArea
                  name="reason"
                  label="Lý do bảo trì"
                  required
                  maxLength={1200}
                />
              </div>
            </fieldset>
            <Feedback action={action} />
            <div className="form-footer">
              <p className="small muted">
                Kiểm tra lịch booking trước khi thêm bảo trì; booking đã xác
                nhận vẫn được giữ.
              </p>
              <Button busy={action.busy}>Thêm lịch bảo trì</Button>
            </div>
          </form>
        )}
      </Panel>
      {court && (
        <Panel title="Lịch bảo trì đã thiết lập">
          <ResourceState
            resource={r}
            empty={r.data?.filter((x) => !x.cancelled).length === 0}
            emptyTitle="Sân chưa có lịch bảo trì"
            emptyDescription="Lịch bảo trì đang hoạt động sẽ hiển thị tại đây."
          >
            <Table headers={["Thời gian", "Lý do", "Thao tác"]}>
              {r.data
                ?.filter((x) => !x.cancelled)
                .map((x) => (
                  <tr key={x.id}>
                    <td>
                      {dateTime(x.startsAt)}
                      <small>đến {dateTime(x.endsAt)}</small>
                    </td>
                    <td style={{ whiteSpace: "normal" }}>{x.reason}</td>
                    <td>
                      {editable && (
                        <Button variant="ghost" onClick={() => setRemove(x.id)}>
                          Hủy bảo trì
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
            </Table>
          </ResourceState>
        </Panel>
      )}
      <Modal
        open={!!remove}
        title="Hủy lịch bảo trì?"
        onClose={() => setRemove(null)}
      >
        <p className="muted small">
          Khung giờ sẽ được tính lại theo lịch hoạt động của sân.
        </p>
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setRemove(null)}>
            Quay lại
          </Button>
          <Button
            variant="danger"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () => api(`/owner/maintenance/${remove}`, { method: "DELETE" }),
                () => {
                  setRemove(null);
                  r.reload();
                },
              );
            }}
          >
            Hủy bảo trì
          </Button>
        </div>
      </Modal>
    </div>
  );
}
const permissions: [string, string][] = [
  ["BOOKING_READ", "Xem booking"],
  ["BOOKING_CREATE_COUNTER", "Đặt sân tại quầy"],
  ["BOOKING_CHECK_IN", "Check-in"],
  ["BOOKING_COMPLETE", "Hoàn tất booking"],
  ["SCHEDULE_READ", "Xem lịch & giá"],
];
export function StaffPage() {
  const { facility: f, editable } = useFacility(),
    r = useResource<Binding[]>(`/staff-bindings/facilities/${f.id}`),
    action = useAction(),
    [remove, setRemove] = useState<string | null>(null),
    [edit, setEdit] = useState<Binding | null>(null);
  return (
    <div className="stack">
      <Panel
        title={edit ? "Cập nhật quyền nhân viên" : "Thêm nhân viên vào cơ sở"}
        description="Nhân viên cần tài khoản ACTIVE có vai trò STAFF trước khi phân công."
      >
        <form
          key={edit?.id ?? "new"}
          onSubmit={(e) => {
            e.preventDefault();
            const values = new FormData(e.currentTarget);
            const form = e.currentTarget;
            void action.run(
              () => {
                if (!values.getAll("permissions").length)
                  throw new Error("Chọn ít nhất một quyền.");
                return api(`/staff-bindings/facilities/${f.id}`, {
                  method: "POST",
                  body: JSON.stringify({
                    identifier: values.get("identifier"),
                    permissions: values.getAll("permissions"),
                  }),
                });
              },
              () => {
                r.reload();
                form.reset();
                setEdit(null);
                action.setMessage("Đã lưu phân công và quyền của nhân viên.");
              },
            );
          }}
        >
          <fieldset disabled={action.busy || !editable}>
            <Field
              name="identifier"
              label="Email hoặc số điện thoại nhân viên"
              required
              defaultValue={edit?.email ?? ""}
            />
            <div className="actions" style={{ marginTop: 20 }}>
              {permissions.map(([id, caption]) => (
                <label key={id} className="small actions">
                  <input
                    type="checkbox"
                    name="permissions"
                    value={id}
                    defaultChecked={
                      edit?.permissions.includes(id) ?? id === "BOOKING_READ"
                    }
                  />
                  {caption}
                </label>
              ))}
            </div>
          </fieldset>
          <Feedback action={action} />
          <div className="form-footer">
            <p className="small muted">Quyền chỉ áp dụng tại cơ sở hiện tại.</p>
            <div className="actions">
              {edit && (
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => setEdit(null)}
                >
                  Hủy chỉnh sửa
                </Button>
              )}
              <Button busy={action.busy} disabled={!editable}>
                Lưu phân quyền
              </Button>
            </div>
          </div>
        </form>
      </Panel>
      <Panel title="Đội ngũ vận hành">
        <ResourceState
          resource={r}
          empty={r.data?.length === 0}
          emptyTitle="Chưa có nhân viên"
          emptyDescription="Liên kết tài khoản Staff để cùng quản lý hoạt động tại sân."
        >
          <Table
            headers={["Nhân viên", "Quyền tại cơ sở", "Trạng thái", "Thao tác"]}
          >
            {r.data?.map((b) => (
              <tr key={b.id}>
                <td>
                  <strong>{b.fullName}</strong>
                  <small>{b.email}</small>
                </td>
                <td style={{ whiteSpace: "normal", maxWidth: 250 }}>
                  {b.permissions
                    .map((p) => permissions.find((x) => x[0] === p)?.[1] ?? p)
                    .join(", ")}
                </td>
                <td>
                  <Badge state={b.active ? "ACTIVE" : "DISABLED"} />
                </td>
                <td>
                  {b.active && editable && (
                    <div className="actions">
                      <Button variant="ghost" onClick={() => setEdit(b)}>
                        Sửa quyền
                      </Button>
                      <Button variant="ghost" onClick={() => setRemove(b.id)}>
                        Thu hồi
                      </Button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        </ResourceState>
      </Panel>
      <Modal
        open={!!remove}
        title="Thu hồi quyền nhân viên?"
        onClose={() => setRemove(null)}
      >
        <p className="muted small">
          Nhân viên sẽ không còn thao tác tại cơ sở này. Dữ liệu lịch sử vẫn
          được giữ.
        </p>
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setRemove(null)}>
            Giữ quyền
          </Button>
          <Button
            variant="danger"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () =>
                  api(`/staff-bindings/facilities/${f.id}/${remove}`, {
                    method: "DELETE",
                  }),
                () => {
                  setRemove(null);
                  r.reload();
                },
              );
            }}
          >
            Thu hồi quyền
          </Button>
        </div>
      </Modal>
    </div>
  );
}
export function DocumentsPage() {
  const { facility: f, editable } = useFacility(),
    r = useResource<DocumentFile[]>(`/owner/facilities/${f.id}/documents`),
    action = useAction(),
    [remove, setRemove] = useState<string | null>(null);
  return (
    <Panel
      title="Tài liệu thẩm định"
      description="Tệp được bảo vệ; chỉ chủ hồ sơ và Admin được truy cập."
    >
      {editable && (
        <form
          className="file-upload"
          onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const body = new FormData(form);
            void action.run(
              () => {
                validateFile(body.get("file"));
                return api(`/owner/facilities/${f.id}/documents`, {
                  method: "POST",
                  body,
                });
              },
              () => {
                form.reset();
                r.reload();
                action.setMessage("Đã tải tài liệu lên.");
              },
            );
          }}
        >
          <UploadField label="Thêm tài liệu xác minh" />
          <Button busy={action.busy} style={{ marginTop: 16 }}>
            Tải tài liệu lên
          </Button>
        </form>
      )}
      <Feedback action={action} />
      <div style={{ marginTop: 24 }}>
        <ResourceState
          resource={r}
          empty={r.data?.filter((x) => !x.archived).length === 0}
          emptyTitle="Chưa có tài liệu"
          emptyDescription="Tải giấy tờ định danh và giấy tờ sử dụng địa điểm trước khi gửi hồ sơ."
        >
          <Table headers={["Tài liệu", "Dung lượng", "Thao tác"]}>
            {r.data
              ?.filter((x) => !x.archived)
              .map((d) => (
                <tr key={d.id}>
                  <td>
                    <a href={d.url} target="_blank" rel="noreferrer">
                      {d.name}{" "}
                      <ArrowUpRight size={12} style={{ display: "inline" }} />
                    </a>
                  </td>
                  <td>{(d.sizeBytes / 1024).toFixed(1)} KB</td>
                  <td>
                    {editable && (
                      <Button variant="ghost" onClick={() => setRemove(d.id)}>
                        Gỡ tài liệu
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
          </Table>
        </ResourceState>
      </div>
      <Modal
        open={!!remove}
        title="Gỡ tài liệu này?"
        onClose={() => setRemove(null)}
      >
        <p className="muted small">
          Tài liệu đã nộp ở các lần kiểm duyệt trước vẫn được bảo toàn trong
          lịch sử.
        </p>
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setRemove(null)}>
            Giữ tài liệu
          </Button>
          <Button
            variant="danger"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () =>
                  api(`/owner/facilities/${f.id}/documents/${remove}`, {
                    method: "DELETE",
                  }),
                () => {
                  setRemove(null);
                  r.reload();
                },
              );
            }}
          >
            Gỡ tài liệu
          </Button>
        </div>
      </Modal>
    </Panel>
  );
}
export function ReviewPage() {
  const { facility: f, reload, editable } = useFacility(),
    r = useResource<FacilityReview[]>(`/owner/facilities/${f.id}/reviews`),
    action = useAction(),
    [confirm, setConfirm] = useState(false);
  return (
    <div className="stack">
      <Panel title="Đưa cơ sở lên SportHub">
        <p className="muted small">
          Hoàn thiện thông tin, sân, ảnh, tài liệu, giờ hoạt động và bảng giá
          trước khi gửi thẩm định.
        </p>
        {f.status === "DRAFT" && (
          <Button onClick={() => setConfirm(true)} style={{ marginTop: 24 }}>
            Gửi cơ sở để kiểm duyệt
          </Button>
        )}
        {f.status === "ACTIVE" && (
          <Notice tone="success">
            Cơ sở đã được duyệt và có thể nhận booking theo lịch khả dụng.
          </Notice>
        )}
      </Panel>
      <Panel title="Lịch sử thẩm định">
        <ResourceState
          resource={r}
          empty={r.data?.length === 0}
          emptyTitle="Chưa gửi hồ sơ"
          emptyDescription="Lịch sử thẩm định sẽ xuất hiện sau lần gửi đầu tiên."
        >
          <ol className="timeline">
            {r.data?.map((x) => (
              <li key={x.id}>
                <Badge state={x.state} />
                <small>{dateTime(x.submittedAt)}</small>
                {x.reason && (
                  <p className="small" style={{ marginTop: 8 }}>
                    {x.reason}
                  </p>
                )}
              </li>
            ))}
          </ol>
        </ResourceState>
      </Panel>
      <Modal
        open={confirm}
        title="Gửi hồ sơ cơ sở?"
        onClose={() => setConfirm(false)}
      >
        <Notice>
          Thông tin đã gửi sẽ được giữ nguyên trong thời gian Admin kiểm duyệt.
        </Notice>
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setConfirm(false)}>
            Kiểm tra lại
          </Button>
          <Button
            busy={action.busy}
            disabled={!editable}
            onClick={() => {
              void action.run(
                () =>
                  api(`/owner/facilities/${f.id}/submit`, { method: "POST" }),
                () => {
                  setConfirm(false);
                  reload();
                  r.reload();
                },
              );
            }}
          >
            Xác nhận gửi hồ sơ
          </Button>
        </div>
      </Modal>
    </div>
  );
}
export function TransferPolicyPage() {
  const { facility: f, editable } = useFacility(),
    r = useResource<{
      configured: boolean;
      enabled: boolean;
      minLeadSeconds: number;
    }>(`/owner/facilities/${f.id}/transfer-policy`),
    action = useAction();
  return (
    <Panel
      title="Chính sách chuyển nhượng"
      description="Điều kiện này áp dụng cho booking tại riêng cơ sở của bạn."
    >
      <ResourceState resource={r}>
        {r.data && (
          <form
            key={JSON.stringify(r.data)}
            onSubmit={(e) => {
              e.preventDefault();
              const values = new FormData(e.currentTarget);
              void action.run(
                () =>
                  api(`/owner/facilities/${f.id}/transfer-policy`, {
                    method: "PUT",
                    body: JSON.stringify({
                      enabled: values.get("enabled") === "on",
                      minLeadSeconds: Number(values.get("minutes")) * 60,
                    }),
                  }),
                () => {
                  r.reload();
                  action.setMessage("Đã cập nhật chính sách chuyển nhượng.");
                },
              );
            }}
          >
            {!r.data.configured && (
              <Notice tone="warning">
                Chưa cấu hình chính sách. Lịch đặt tại cơ sở hiện không thể
                chuyển nhượng.
              </Notice>
            )}
            <fieldset disabled={action.busy || !editable} className="stack">
              <label className="small actions">
                <input
                  name="enabled"
                  type="checkbox"
                  defaultChecked={r.data.enabled}
                />
                Cho phép chuyển nhượng booking đủ điều kiện
              </label>
              <Field
                name="minutes"
                label="Thời gian tối thiểu trước giờ chơi (phút)"
                type="number"
                min={0}
                max={129600}
                step={1}
                required
                defaultValue={r.data.minLeadSeconds / 60}
              />
            </fieldset>
            <Feedback action={action} />
            <div className="form-footer">
              <Button busy={action.busy} disabled={!editable}>
                Lưu chính sách
              </Button>
            </div>
          </form>
        )}
      </ResourceState>
    </Panel>
  );
}
