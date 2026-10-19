"use client";
import { Photo } from "@/rebuilt/components/photo";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { api } from "@/rebuilt/lib/api";
import { useAuth } from "@/rebuilt/lib/auth-context";
import { useAction, useResource, formQuery } from "@/rebuilt/lib/hooks";
import {
  type ApplicationDetail,
  type DocumentFile,
  type Media,
  type OwnerApplication,
  dateTime,
  label,
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
  Table,
  Tabs,
  TextArea,
} from "@/rebuilt/components/ui";
import {
  ApplicationDisclosure,
  FacilityFields,
  LegalFields,
  facilityFrom,
  legalFields,
  legalFrom,
} from "@/rebuilt/components/domain-forms";
import { Records } from "@/rebuilt/components/records";
import { FacilityWorkspace } from "./facilities";
export function OwnerApplyPage() {
  const { user } = useAuth(),
    r = useResource<OwnerApplication[]>("/owner-applications"),
    action = useAction(),
    router = useRouter();
  if (user?.roles.includes("OWNER"))
    return (
      <>
        <PageHead
          title="Bạn đã là đối tác SportHub."
          description="Tạo thêm cơ sở và bắt đầu quản lý sân của bạn."
        />
        <LinkButton href="/owner/facilities" arrow>
          Đến cơ sở của tôi
        </LinkButton>
      </>
    );
  return (
    <>
      <PageHead
        eyebrow="Đăng ký chủ sân"
        title="Đưa cơ sở của bạn lên SportHub."
        description="Tạo hồ sơ chủ sân và cơ sở đầu tiên để bắt đầu hành trình đối tác."
      />
      <ResourceState resource={r}>
        {r.data?.some((x) => x.state !== "REJECTED") ? (
          <Panel title="Bạn đã có hồ sơ đang xử lý">
            <p className="muted small">
              Tiếp tục hoàn thiện hoặc theo dõi hồ sơ đã tạo.
            </p>
            <LinkButton
              href={`/owner/application/${r.data.find((x) => x.state !== "REJECTED")!.id}`}
              arrow
            >
              Mở hồ sơ của tôi
            </LinkButton>
          </Panel>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              void action.run(
                () =>
                  api<OwnerApplication>("/owner-applications", {
                    method: "POST",
                    body: JSON.stringify({
                      legal: legalFrom(f),
                      facility: facilityFrom(f, "facilityName"),
                    }),
                  }),
                (a) => router.push(`/owner/application/${a.id}`),
              );
            }}
          >
            <fieldset disabled={action.busy} className="stack">
              <Panel title="1. Chủ sân & pháp lý">
                <ApplicationDisclosure />
                <div className="form-grid">
                  <LegalFields errors={action.fields} />
                </div>
              </Panel>
              <Panel title="2. Cơ sở đầu tiên">
                <div className="form-grid">
                  <FacilityFields
                    nameField="facilityName"
                    coordinatesRequired
                    errors={action.fields}
                  />
                </div>
              </Panel>
            </fieldset>
            <Feedback action={action} />
            <div className="form-footer">
              <p className="muted small">
                Tiếp theo: thêm sân, ảnh, tài liệu, lịch và giá trong hồ sơ
                nháp.
              </p>
              <Button busy={action.busy}>Tạo hồ sơ đối tác</Button>
            </div>
          </form>
        )}
      </ResourceState>
    </>
  );
}
export function ApplicationsPage({ admin = false }: { admin?: boolean }) {
  const params = useSearchParams(),
    router = useRouter(),
    query = params.toString();
  const r = useResource<OwnerApplication[]>(
      admin ? null : "/owner-applications",
    ),
    paged = useResource<{
      items: OwnerApplication[];
      page: number;
      size: number;
      totalPages: number;
      totalElements: number;
    }>(admin ? "/admin/owner-applications/page?" + query : null);
  const data = admin ? paged.data?.items : r.data,
    res = admin ? paged : r;
  function page(value: number) {
    const p = new URLSearchParams(query);
    p.set("page", String(value));
    router.push("/admin/owner-applications?" + p);
  }
  return (
    <>
      <PageHead
        eyebrow={admin ? "Duyệt hồ sơ chủ sân" : "Hồ sơ của bạn"}
        title={admin ? "Hồ sơ đăng ký chủ sân" : "Hồ sơ đối tác của bạn"}
        description={
          admin
            ? "Kiểm tra tài khoản, pháp lý và cơ sở đầu tiên trước khi quyết định."
            : "Theo dõi các bước chuẩn bị, bổ sung và kết quả thẩm định."
        }
        action={
          !admin ? (
            <LinkButton href="/owner/apply">Đăng ký đối tác</LinkButton>
          ) : undefined
        }
      />
      {admin && (
        <Panel title="Tra cứu hồ sơ">
          <form
            key={query}
            className="form-grid"
            onSubmit={(e) => {
              e.preventDefault();
              router.push(
                "/admin/owner-applications?" + formQuery(e.currentTarget),
              );
            }}
          >
            <Field
              label="Từ khóa"
              name="q"
              defaultValue={params.get("q") ?? ""}
              maxLength={180}
            />
            <Select
              label="Trạng thái"
              name="state"
              defaultValue={params.get("state") ?? ""}
            >
              <option value="">Tất cả</option>
              {[
                "DRAFT",
                "SUBMITTING",
                "PENDING_APPROVAL",
                "SUPPLEMENT_REQUIRED",
                "APPROVING",
                "APPROVED",
                "REJECTED",
              ].map((s) => (
                <option key={s} value={s}>
                  {label(s)}
                </option>
              ))}
            </Select>
            <Field
              name="applicant"
              label="Tài khoản đăng ký (tên, email, SĐT)"
              defaultValue={params.get("applicant") ?? ""}
              maxLength={254}
            />
            <Field
              name="facility"
              label="Tên hoặc địa chỉ cơ sở"
              defaultValue={params.get("facility") ?? ""}
              maxLength={180}
            />
            <Field
              name="submittedFrom"
              label="Ngày nộp từ"
              type="date"
              defaultValue={params.get("submittedFrom") ?? ""}
            />
            <Field
              name="submittedTo"
              label="Ngày nộp đến"
              type="date"
              defaultValue={params.get("submittedTo") ?? ""}
            />
            <Field
              name="reviewedFrom"
              label="Ngày xử lý từ"
              type="date"
              defaultValue={params.get("reviewedFrom") ?? ""}
            />
            <Field
              name="reviewedTo"
              label="Ngày xử lý đến"
              type="date"
              defaultValue={params.get("reviewedTo") ?? ""}
            />
            <Field
              name="reviewedBy"
              label="Mã Admin xử lý"
              defaultValue={params.get("reviewedBy") ?? ""}
            />
            <Field
              name="applicationId"
              label="Mã hồ sơ"
              defaultValue={params.get("applicationId") ?? ""}
            />
            <Select
              name="sort"
              label="Thứ tự"
              defaultValue={params.get("sort") ?? "SUBMITTED_ASC"}
            >
              <option value="SUBMITTED_ASC">Nộp cũ trước</option>
              <option value="SUBMITTED_DESC">Nộp mới trước</option>
            </Select>
            <Select
              name="size"
              label="Số hồ sơ mỗi trang"
              defaultValue={params.get("size") ?? 20}
            >
              {[10, 20, 50, 100].map((s) => (
                <option value={s} key={s}>
                  {s}
                </option>
              ))}
            </Select>
            <div className="actions span-2">
              <Button>Áp dụng bộ lọc</Button>
              <Link
                className="small text-link"
                href="/admin/owner-applications"
              >
                Đặt lại
              </Link>
            </div>
          </form>
        </Panel>
      )}
      <Panel title="Danh sách hồ sơ" className={admin ? "mt-6" : ""}>
        <ResourceState
          resource={res}
          empty={data?.length === 0}
          emptyTitle="Chưa có hồ sơ phù hợp"
          emptyDescription="Hồ sơ sẽ xuất hiện tại đây sau khi người dùng đăng ký đối tác."
        >
          <Table
            headers={[
              "Hồ sơ / Đơn vị",
              "Cơ sở đầu tiên",
              "Trạng thái",
              "Ngày gửi",
              "Chi tiết",
            ]}
          >
            {data?.map((a) => (
              <tr key={a.id}>
                <td>
                  <strong>{a.businessName}</strong>
                  <small>#{shortId(a.id)}</small>
                </td>
                <td>{a.facilityName}</td>
                <td>
                  <Badge state={a.state} />
                </td>
                <td>{a.submittedAt ? dateTime(a.submittedAt) : "Chưa gửi"}</td>
                <td>
                  <Link
                    href={`${admin ? "/admin/owner-applications/" : "/owner/application/"}${a.id}`}
                  >
                    Mở hồ sơ
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        </ResourceState>
        {admin && paged.data && (
          <div className="form-footer">
            <p className="small muted">
              {paged.data.totalElements} hồ sơ · Trang {paged.data.page + 1}/
              {Math.max(1, paged.data.totalPages)}
            </p>
            <div className="actions">
              <Button
                variant="secondary"
                disabled={paged.data.page === 0}
                onClick={() => page(paged.data!.page - 1)}
              >
                <ChevronLeft size={15} />
                Trước
              </Button>
              <Button
                variant="secondary"
                disabled={paged.data.page + 1 >= paged.data.totalPages}
                onClick={() => page(paged.data!.page + 1)}
              >
                Sau
                <ChevronRight size={15} />
              </Button>
            </div>
          </div>
        )}
      </Panel>
    </>
  );
}
export function ApplicationPage({
  id,
  admin = false,
  legal = false,
}: {
  id: string;
  admin?: boolean;
  legal?: boolean;
}) {
  const r = useResource<ApplicationDetail>(
      `${admin ? "/admin" : ""}/owner-applications/${id}`,
    ),
    action = useAction(),
    [confirm, setConfirm] = useState(false),
    [tab, setTab] = useState("current"),
    router = useRouter(),
    [decision, setDecision] = useState("APPROVE"),
    [historyPage, setHistoryPage] = useState(0);
  const history = useResource<{
    items: {
      id: string;
      actorName: string | null;
      action: string;
      occurredAt: string;
      fromState: string | null;
      toState: string | null;
      reason: string | null;
      changedFields: string[];
      metadataAvailable: boolean;
    }[];
    page: number;
    totalPages: number;
  }>(
    admin
      ? `/admin/owner-applications/${id}/history?page=${historyPage}&size=20`
      : null,
  );
  const a = r.data?.application,
    editable = a && ["DRAFT", "SUPPLEMENT_REQUIRED"].includes(a.state);
  return (
    <>
      <PageHead
        eyebrow={admin ? "Duyệt hồ sơ chủ sân" : "Hồ sơ đăng ký"}
        title={`Hồ sơ #${shortId(id)}`}
        description={a?.businessName}
        action={
          <Button
            variant="secondary"
            onClick={() => {
              r.reload();
              history.reload();
            }}
          >
            Cập nhật hồ sơ
          </Button>
        }
      />
      <Feedback action={action} />
      {!admin && r.error && (
        <Button
          variant="secondary"
          busy={action.busy}
          onClick={() => {
            void action.run(
              () =>
                api(`/owner-applications/${id}/initialize`, { method: "POST" }),
              r.reload,
            );
          }}
        >
          Khôi phục cơ sở nháp
        </Button>
      )}
      <ResourceState resource={r}>
        {r.data && a && (
          <>
            <div className="actions" style={{ marginBottom: 24 }}>
              <Badge state={a.state} />
              {a.reason && <Notice tone="warning">{a.reason}</Notice>}
            </div>
            {a.lastError && <Notice tone="warning">{a.lastError}</Notice>}
            {legal && !admin ? (
              <Panel title="Cập nhật hồ sơ pháp lý">
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    void action.run(
                      () =>
                        api(`/owner-applications/${id}/legal`, {
                          method: "PUT",
                          body: JSON.stringify(legalFrom(f)),
                        }),
                      () => router.push(`/owner/application/${id}`),
                    );
                  }}
                >
                  <fieldset disabled={!editable || action.busy}>
                    <div className="form-grid">
                      <LegalFields
                        value={r.data.legal}
                        errors={action.fields}
                      />
                    </div>
                  </fieldset>
                  <Feedback action={action} />
                  <div className="form-footer">
                    <Link
                      href={`/owner/application/${id}`}
                      className="small text-link"
                    >
                      Quay lại hồ sơ
                    </Link>
                    <Button disabled={!editable} busy={action.busy}>
                      Lưu thông tin pháp lý
                    </Button>
                  </div>
                </form>
              </Panel>
            ) : (
              <>
                <div className="grid-2">
                  <Panel title="Thông tin pháp lý">
                    <ApplicationDisclosure />
                    <DetailList
                      items={legalFields.map(([name, caption]) => [
                        caption,
                        r.data!.legal[name] ?? "Chưa khai báo",
                      ])}
                    />
                    {!admin && editable && (
                      <LinkButton
                        href={`/owner/application/${id}/legal`}
                        variant="secondary"
                      >
                        Chỉnh sửa pháp lý
                      </LinkButton>
                    )}
                    {admin && r.data.applicant && (
                      <div style={{ marginTop: 24 }}>
                        <h3>Tài khoản đăng ký</h3>
                        <DetailList
                          items={[
                            ["Họ tên", r.data.applicant.fullName],
                            ["Email", r.data.applicant.email],
                            ["Số điện thoại", r.data.applicant.phone],
                            ["Trạng thái", label(r.data.applicant.status)],
                          ]}
                        />
                        <Link
                          className="text-link small"
                          href={`/admin/accounts/${r.data.applicant.id}`}
                        >
                          Xem tài khoản liên quan →
                        </Link>
                      </div>
                    )}
                  </Panel>
                  <Panel title="Cơ sở đầu tiên">
                    <h3>{a.facilityName}</h3>
                    {r.data.facility?.facility && (
                      <DetailList
                        items={[
                          ["Địa chỉ", r.data.facility.facility.addressLine],
                          ["Múi giờ", r.data.facility.facility.timezone],
                          ["Sân", r.data.facility.courts.length],
                          [
                            "Trạng thái cơ sở",
                            label(r.data.facility.facility.status),
                          ],
                        ]}
                      />
                    )}
                    {!admin && (
                      <div className="stack" style={{ marginTop: 20 }}>
                        <LinkButton
                          href={`/owner/application/${id}/facility`}
                          variant="secondary"
                          arrow
                        >
                          Hoàn thiện cơ sở
                        </LinkButton>
                        {editable && (
                          <Button onClick={() => setConfirm(true)}>
                            Gửi hồ sơ thẩm định
                          </Button>
                        )}
                      </div>
                    )}
                    {admin && a.state === "PENDING_APPROVAL" && (
                      <form
                        style={{ marginTop: 20 }}
                        onSubmit={(e) => {
                          e.preventDefault();
                          setConfirm(true);
                        }}
                      >
                        <div className="stack">
                          <Select
                            label="Quyết định thẩm định"
                            value={decision}
                            onChange={(e) => setDecision(e.target.value)}
                          >
                            <option value="APPROVE">Phê duyệt hồ sơ</option>
                            <option value="SUPPLEMENT_REQUIRED">
                              Yêu cầu bổ sung
                            </option>
                            <option value="REJECT">Từ chối hồ sơ</option>
                          </Select>
                          <Button>Xem & xác nhận quyết định</Button>
                        </div>
                      </form>
                    )}
                    {admin &&
                      ["SUBMITTING", "APPROVING", "DECIDING"].includes(
                        a.state,
                      ) && (
                        <Button
                          busy={action.busy}
                          onClick={() => {
                            void action.run(
                              () =>
                                api(`/admin/owner-applications/${id}/retry`, {
                                  method: "POST",
                                }),
                              () => {
                                r.reload();
                                history.reload();
                              },
                            );
                          }}
                        >
                          Tiếp tục xử lý an toàn
                        </Button>
                      )}
                  </Panel>
                </div>
                {admin && (
                  <div style={{ marginTop: 26 }}>
                    <Tabs
                      value={tab}
                      onChange={setTab}
                      items={[
                        ["current", "Hồ sơ hiện tại"],
                        ["snapshot", "Bản đã gửi"],
                        ["history", "Lịch sử xử lý"],
                      ]}
                    />
                    {tab === "current" ? (
                      <AdminApplicationAssets data={r.data} />
                    ) : tab === "snapshot" ? (
                      <Panel title="Hồ sơ tại thời điểm nộp">
                        {r.data.reviewSnapshot ? (
                          <Records value={r.data.reviewSnapshot} />
                        ) : (
                          r.data.history.map((h) => (
                            <div
                              key={h.id}
                              className="record"
                              style={{ marginBottom: 16 }}
                            >
                              <h3>{dateTime(h.submittedAt)}</h3>
                              <Records value={h.facilitySnapshot} />
                            </div>
                          ))
                        )}
                      </Panel>
                    ) : (
                      <Panel title="Lịch sử chỉnh sửa & quyết định">
                        <ResourceState resource={history}>
                          <ol className="timeline">
                            {history.data?.items.map((h) => (
                              <li key={h.id}>
                                <strong>{label(h.action)}</strong>
                                <small>
                                  {h.actorName ??
                                    "Không có thông tin người thực hiện"}{" "}
                                  · {dateTime(h.occurredAt)}
                                </small>
                                {h.fromState && h.toState && (
                                  <p className="small">
                                    {label(h.fromState)} → {label(h.toState)}
                                  </p>
                                )}
                                {h.reason && (
                                  <p className="small">{h.reason}</p>
                                )}
                                {h.changedFields.length > 0 && (
                                  <p className="small muted">
                                    Trường sửa:{" "}
                                    {h.changedFields
                                      .map(
                                        (k) =>
                                          legalFields.find(
                                            (x) => x[0] === k,
                                          )?.[1] ?? k,
                                      )
                                      .join(", ")}
                                  </p>
                                )}
                                {!h.metadataAvailable && (
                                  <p className="small muted">
                                    Bản ghi cũ chưa lưu đầy đủ metadata; không
                                    suy đoán thay đổi.
                                  </p>
                                )}
                              </li>
                            ))}
                          </ol>
                          <div className="form-footer">
                            <Button
                              variant="secondary"
                              disabled={!historyPage}
                              onClick={() => setHistoryPage(historyPage - 1)}
                            >
                              Trước
                            </Button>
                            <Button
                              variant="secondary"
                              disabled={
                                historyPage + 1 >=
                                (history.data?.totalPages ?? 0)
                              }
                              onClick={() => setHistoryPage(historyPage + 1)}
                            >
                              Sau
                            </Button>
                          </div>
                        </ResourceState>
                      </Panel>
                    )}
                  </div>
                )}
              </>
            )}
          </>
        )}
      </ResourceState>
      <Modal
        open={confirm}
        title={admin ? "Xác nhận quyết định hồ sơ" : "Gửi hồ sơ thẩm định?"}
        onClose={() => setConfirm(false)}
      >
        {admin ? (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              void action.run(
                () =>
                  api(`/admin/owner-applications/${id}/decision`, {
                    method: "POST",
                    body: JSON.stringify({
                      action: decision,
                      reason: f.get("reason"),
                      commissionPercent:
                        decision === "APPROVE"
                          ? Number(f.get("commissionPercent"))
                          : null,
                    }),
                  }),
                () => {
                  setConfirm(false);
                  r.reload();
                  history.reload();
                },
              );
            }}
          >
            <Notice>
              {decision === "APPROVE"
                ? "Phê duyệt sẽ cấp quyền Owner và kích hoạt cơ sở đầu tiên."
                : decision === "REJECT"
                  ? "Từ chối hồ sơ; tài khoản Customer vẫn được giữ."
                  : "Mở lại hồ sơ để người đăng ký bổ sung thông tin."}
            </Notice>
            {decision === "APPROVE" && (
              <Field
                name="commissionPercent"
                label="Hoa hồng nền tảng (%)"
                type="number"
                min={0}
                max={100}
                step="0.01"
                required
              />
            )}
            <TextArea
              name="reason"
              label="Lý do / ghi chú quyết định"
              required
              maxLength={1200}
              minLength={decision === "APPROVE" ? 1 : 20}
            />
            <Feedback action={action} />
            <div className="form-footer">
              <Button
                type="button"
                variant="secondary"
                onClick={() => setConfirm(false)}
              >
                Kiểm tra lại
              </Button>
              <Button busy={action.busy}>Xác nhận quyết định</Button>
            </div>
          </form>
        ) : (
          <>
            <Notice>
              Kiểm tra ít nhất một sân, ảnh cơ sở, hai tài liệu pháp lý, giờ
              hoạt động và giá. Hồ sơ sẽ được giữ nguyên trong thời gian kiểm
              duyệt.
            </Notice>
            <Feedback action={action} />
            <div className="form-footer">
              <Button variant="secondary" onClick={() => setConfirm(false)}>
                Kiểm tra lại
              </Button>
              <Button
                busy={action.busy}
                onClick={() => {
                  void action.run(
                    () =>
                      api(`/owner-applications/${id}/submit`, {
                        method: "POST",
                      }),
                    () => {
                      setConfirm(false);
                      r.reload();
                    },
                  );
                }}
              >
                Gửi hồ sơ
              </Button>
            </div>
          </>
        )}
      </Modal>
    </>
  );
}
function AdminApplicationAssets({ data }: { data: ApplicationDetail }) {
  const id = data.application.facilityId,
    documents = useResource<DocumentFile[]>(
      `/admin/facilities/${id}/documents`,
    ),
    images = useResource<Media[]>(`/admin/facilities/${id}/images`);
  return (
    <div className="stack">
      <Panel title="Cơ sở & sân hiện tại">
        <Records value={data.facility} />
      </Panel>
      <Panel title="Tài liệu thẩm định">
        <ResourceState resource={documents}>
          <div className="court-list">
            {documents.data?.map((d) => (
              <a
                className="court-row"
                key={d.id}
                href={d.url}
                target="_blank"
                rel="noreferrer"
              >
                <span className="small">
                  {d.name}
                  {d.archived ? " · Tài liệu đã lưu lịch sử" : ""}
                </span>
                <span className="small muted">
                  {(d.sizeBytes / 1024).toFixed(1)} KB ↗
                </span>
              </a>
            ))}
          </div>
        </ResourceState>
      </Panel>
      <Panel title="Ảnh cơ sở">
        <ResourceState resource={images}>
          <div className="grid-3">
            {images.data?.map((m) => (
              <Photo
                key={m.id}
                src={m.url}
                alt="Ảnh cơ sở trong hồ sơ thẩm định"
                style={{
                  height: 180,
                  objectFit: "cover",
                  width: "100%",
                  borderRadius: 10,
                }}
              />
            ))}
          </div>
        </ResourceState>
      </Panel>
    </div>
  );
}
export function ApplicationFacility({
  id,
  children,
}: {
  id: string;
  children: React.ReactNode;
}) {
  const r = useResource<ApplicationDetail>(`/owner-applications/${id}`);
  return (
    <ResourceState resource={r}>
      {r.data && (
        <FacilityWorkspace
          id={r.data.application.facilityId}
          applicationId={id}
        >
          {children}
        </FacilityWorkspace>
      )}
    </ResourceState>
  );
}
