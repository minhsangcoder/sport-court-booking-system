"use client";
import { Photo } from "@/rebuilt/components/photo";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { ArrowRight, Eye, EyeOff, ShieldCheck } from "lucide-react";
import { api, authApi, refreshSession, type UserProfile } from "@/rebuilt/lib/api";
import { useAction, useIdempotency, useResource } from "@/rebuilt/lib/hooks";
import { safeNext } from "@/rebuilt/lib/selection";
import { rememberGoogleNext } from "@/features/auth/google-auth-state";
import { label } from "@/rebuilt/lib/domain";
import {
  Button,
  Feedback,
  Field,
  LinkButton,
  Notice,
  PageHead,
  Panel,
  ResourceState,
} from "@/rebuilt/components/ui";
import {
  ApplicationDisclosure,
  FacilityFields,
  LegalFields,
  SignupSetup,
  UploadField,
  facilityFrom,
  legalFrom,
  setupFrom,
  validateFile,
} from "@/rebuilt/components/domain-forms";
type Mode = "login" | "register" | "verify" | "forgot" | "reset";
const titles = {
  login: "Rất vui được gặp lại bạn.",
  register: "Cuộc chơi bắt đầu từ đây.",
  verify: "Xác minh tài khoản của bạn.",
  forgot: "Lấy lại quyền truy cập.",
  reset: "Một mật khẩu mới.",
};
export function AuthPage({ mode }: { mode: Mode }) {
  const router = useRouter(),
    params = useSearchParams(),
    action = useAction(),
    key = useIdempotency();
  const [show, setShow] = useState(false),
    [owner, setOwner] = useState(false);
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget),
      v = (k: string) => String(f.get(k) ?? "").trim();
    await action.run(async () => {
      if (mode === "login") {
        const result = await authApi.login(
          v("identifier"),
          String(f.get("password")),
        );
        const fallback = result.user.roles.includes("ADMIN")
          ? "/admin"
          : result.user.roles.includes("OWNER")
            ? "/owner"
            : result.user.roles.includes("STAFF")
              ? "/staff"
              : "/customer/bookings";
        router.push(safeNext(params.get("next"), fallback));
      } else if (mode === "register") {
        const identifier = v("identifier");
        if (
          !identifier.includes("@") &&
          !/^\+[1-9][0-9]{7,14}$/.test(identifier)
        )
          throw new Error(
            "Nhập email hợp lệ hoặc số điện thoại quốc tế, ví dụ +84901234567.",
          );
        if (f.get("password") !== f.get("confirmPassword"))
          throw new Error("Mật khẩu xác nhận chưa trùng khớp.");
        const account = {
          fullName: v("fullName"),
          password: String(f.get("password")),
          ...(identifier.includes("@")
            ? { email: identifier }
            : { phone: identifier }),
        };
        let result;
        if (owner) {
          const payload = {
            ...account,
            applyAsOwner: true,
            ownerApplication: {
              legal: legalFrom(f),
              facility: facilityFrom(f, "facilityName"),
              setup: setupFrom(f),
            },
          };
          const body = new FormData();
          body.set(
            "request",
            new Blob([JSON.stringify(payload)], { type: "application/json" }),
          );
          const files = [
            "identityDocument",
            "locationDocument",
            "facilityImage",
          ].map((name) => validateFile(f.get(name), name === "facilityImage"));
          files.forEach((file, i) =>
            body.set(
              ["identityDocument", "locationDocument", "facilityImage"][i],
              file,
            ),
          );
          const fingerprint =
            JSON.stringify(payload) +
            files.map((x) => x.name + x.size + x.lastModified).join("|");
          result = await authApi.registerOwner(body, key(fingerprint));
        } else result = await authApi.register(account);
        const p = new URLSearchParams({
          challengeId: result.verificationChallengeId,
        });
        if (result.ownerApplication) {
          p.set("ownerApplicationId", result.ownerApplication.id);
          p.set("ownerState", result.ownerApplication.state);
        }
        router.push("/verify?" + p);
      } else if (mode === "verify") {
        await authApi.verify(
          params.get("token")
            ? { verificationToken: params.get("token")! }
            : { challengeId: v("challengeId"), code: v("code") },
        );
        action.setMessage(
          "Đã xác minh thành công. Bạn có thể đăng nhập để tiếp tục.",
        );
      } else if (mode === "forgot") {
        await authApi.forgot(v("identifier"));
        action.setMessage(
          "Nếu thông tin liên hệ thuộc một tài khoản hợp lệ, mã khôi phục sẽ được gửi đến bạn.",
        );
      } else {
        if (f.get("password") !== f.get("confirmPassword"))
          throw new Error("Mật khẩu xác nhận chưa trùng khớp.");
        await authApi.reset({
          challengeId: v("challengeId"),
          code: v("code"),
          newPassword: String(f.get("password")),
        });
        action.setMessage(
          "Mật khẩu đã được cập nhật. Hãy đăng nhập bằng mật khẩu mới.",
        );
      }
    });
  }
  return (
    <div
      className="auth-layout"
      style={owner ? { gridTemplateColumns: "1fr" } : undefined}
    >
      {!owner && (
        <aside className="auth-art">
          <Photo src="/images/court-aerial.jpg" alt="" />
          <div>
            <p className="eyebrow" style={{ color: "var(--lime)" }}>
              Thêm vận động, thêm kết nối
            </p>
            <h2>
              Dành thời gian
              <br />
              cho cuộc chơi.
            </h2>
            <p>
              Sân yêu thích. Đồng đội thân quen. Những khoảnh khắc bắt đầu từ
              SportHub.
            </p>
          </div>
        </aside>
      )}
      <div className="auth-form-wrap">
        <div
          className="auth-form"
          style={owner ? { maxWidth: 850 } : undefined}
        >
          <p className="eyebrow">Chào mừng đến SportHub</p>
          <h1>{titles[mode]}</h1>
          <p>
            {mode === "login"
              ? "Đăng nhập để tiếp tục những cuộc chơi của bạn."
              : mode === "register"
                ? "Tìm sân, đặt lịch và kết nối cùng nhóm của bạn."
                : "Giữ tài khoản của bạn an toàn và luôn sẵn sàng."}
          </p>
          <form onSubmit={submit}>
            {mode === "login" && params.has("authError") && (
              <Notice tone="error">
                {params.get("authError") === "google_not_configured"
                  ? "Đăng nhập Google chưa được cấu hình. Bạn có thể dùng email hoặc số điện thoại để tiếp tục."
                  : "Không thể đăng nhập bằng Google. Vui lòng thử lại."}
              </Notice>
            )}
            <fieldset disabled={action.busy} className="stack">
              {mode === "register" && (
                <Field
                  name="fullName"
                  label="Họ và tên"
                  autoComplete="name"
                  minLength={2}
                  maxLength={120}
                  required
                  error={action.fields.fullName}
                />
              )}{" "}
              {["login", "register", "forgot"].includes(mode) && (
                <Field
                  name="identifier"
                  label="Email hoặc số điện thoại quốc tế"
                  autoComplete="username"
                  placeholder="ban@example.com hoặc +84901234567"
                  required
                  error={
                    action.fields.email ??
                    action.fields.phone ??
                    action.fields.identifier
                  }
                />
              )}{" "}
              {["login", "register", "reset"].includes(mode) && (
                <div style={{ position: "relative" }}>
                  <Field
                    name="password"
                    label={mode === "reset" ? "Mật khẩu mới" : "Mật khẩu"}
                    type={show ? "text" : "password"}
                    minLength={mode === "login" ? 1 : 8}
                    maxLength={72}
                    autoComplete={
                      mode === "login" ? "current-password" : "new-password"
                    }
                    required
                    error={action.fields.password}
                  />
                  <button
                    className="icon-btn"
                    style={{
                      position: "absolute",
                      right: 6,
                      bottom: 4,
                      border: 0,
                    }}
                    type="button"
                    aria-label={show ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
                    onClick={() => setShow(!show)}
                  >
                    {show ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
              )}
              {["register", "reset"].includes(mode) && (
                <Field
                  name="confirmPassword"
                  label="Nhập lại mật khẩu"
                  type="password"
                  autoComplete="new-password"
                  minLength={8}
                  maxLength={72}
                  required
                />
              )}
              {mode === "login" && (
                <div className="auth-links">
                  <span className="muted">Chào mừng bạn trở lại</span>
                  <Link className="text-link" href="/forgot-password">
                    Quên mật khẩu?
                  </Link>
                </div>
              )}
              {mode === "register" && (
                <>
                  <label className="small actions">
                    <input
                      type="checkbox"
                      checked={owner}
                      onChange={(e) => setOwner(e.target.checked)}
                    />
                    Tôi muốn đăng ký trở thành chủ sân
                  </label>
                  {owner && (
                    <>
                      <ApplicationDisclosure />
                      <Panel title="1. Thông tin pháp lý">
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
                      <Panel title="3. Sân và giờ hoạt động">
                        <div className="form-grid">
                          <SignupSetup />
                        </div>
                      </Panel>
                      <Panel title="4. Tài liệu xác minh">
                        <div className="stack">
                          <UploadField
                            name="identityDocument"
                            label="Giấy tờ định danh"
                          />
                          <UploadField
                            name="locationDocument"
                            label="Giấy tờ địa điểm / mặt bằng"
                          />
                          <UploadField
                            name="facilityImage"
                            label="Ảnh giới thiệu cơ sở"
                            image
                          />
                        </div>
                      </Panel>
                    </>
                  )}
                </>
              )}
              {["verify", "reset"].includes(mode) && !params.get("token") && (
                <>
                  <Field
                    name="challengeId"
                    label="Mã yêu cầu xác minh"
                    defaultValue={params.get("challengeId") ?? ""}
                    required
                  />
                  <Field
                    name="code"
                    label="Mã xác minh 6 chữ số"
                    pattern="[0-9]{6}"
                    inputMode="numeric"
                    maxLength={6}
                    autoComplete="one-time-code"
                    required
                  />
                  <p className="muted small">
                    Kiểm tra email hoặc số điện thoại bạn đã đăng ký để nhận mã.
                  </p>
                </>
              )}
              {mode === "verify" && params.has("ownerApplicationId") && (
                <Notice>
                  Tài khoản đã được tạo. Hồ sơ chủ sân đang ở trạng thái{" "}
                  {label(params.get("ownerState") ?? "DRAFT")}. Xác minh và đăng
                  nhập để theo dõi hồ sơ.
                </Notice>
              )}
            </fieldset>
            <Feedback action={action} />
            <Button type="submit" busy={action.busy}>
              {mode === "login"
                ? "Đăng nhập"
                : mode === "register"
                  ? "Tạo tài khoản"
                  : mode === "verify"
                    ? "Xác minh tài khoản"
                    : mode === "forgot"
                      ? "Gửi mã khôi phục"
                      : "Cập nhật mật khẩu"}
              <ArrowRight size={16} />
            </Button>
            {["login", "register"].includes(mode) && (
              <Button
                type="button"
                variant="secondary"
                disabled={action.busy}
                onClick={() => {
                  rememberGoogleNext(params.get("next"));
                  // OAuth must navigate the browser through the gateway's external redirect.
                  // eslint-disable-next-line @next/next/no-location-assign-relative-destination
                  window.location.assign("/api/v1/auth/oauth2/authorization/google");
                }}
              >
                Tiếp tục với Google
              </Button>
            )}
            {action.message && (
              <LinkButton
                href={
                  params.has("ownerApplicationId")
                    ? `/login?next=${encodeURIComponent("/owner/application/" + params.get("ownerApplicationId"))}`
                    : "/login"
                }
                variant="secondary"
              >
                Đến trang đăng nhập
              </LinkButton>
            )}
          </form>
          <div className="auth-note">
            {mode === "login" ? (
              <>
                Chưa có tài khoản? <Link href="/register">Đăng ký ngay</Link>
              </>
            ) : (
              <>
                Đã có tài khoản? <Link href="/login">Đăng nhập</Link>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
export function ProfilePage({ contact = false }: { contact?: boolean }) {
  const resource = useResource<UserProfile>("/users/me"),
    action = useAction();
  const challenges = useResource<
    {
      id: string;
      channel: string;
      recipient: string;
      expiresAt: string;
      resendAvailableAt: string;
      usable: boolean;
    }[]
  >("/users/me/contact-challenges");
  async function save(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const body = contact
      ? {
          ...(f.get("email") ? { email: String(f.get("email")) } : {}),
          ...(f.get("phone") ? { phone: String(f.get("phone")) } : {}),
        }
      : {
          fullName: f.get("fullName"),
          ...(f.get("dateOfBirth")
            ? { dateOfBirth: f.get("dateOfBirth") }
            : {}),
          avatarUrl: f.get("avatarUrl") || null,
        };
    await action.run(
      () => api("/users/me", { method: "PATCH", body: JSON.stringify(body) }),
      () => {
        resource.reload();
        challenges.reload();
        void refreshSession().catch(() => {});
        action.setMessage(
          contact
            ? "Yêu cầu xác minh đã được gửi đến thông tin liên hệ mới."
            : "Hồ sơ đã được cập nhật.",
        );
      },
    );
  }
  return (
    <div className="container narrow">
      <PageHead
        eyebrow="Tài khoản của bạn"
        title="Tài khoản của bạn"
        description="Thông tin cá nhân và cách chúng tôi kết nối với bạn."
      />
      <div className="settings-nav">
        <Link href="/customer/profile" className={!contact ? "active" : ""}>
          Hồ sơ cá nhân
        </Link>
        <Link
          href="/customer/profile/contact"
          className={contact ? "active" : ""}
        >
          Thông tin đăng nhập
        </Link>
        <Link href="/owner/apply">
          Đăng ký chủ sân <ArrowRight size={12} style={{ display: "inline" }} />
        </Link>
      </div>
      <ResourceState resource={resource}>
        {resource.data && (
          <div className="grid-2">
            <Panel
              title={contact ? "Email & số điện thoại" : "Thông tin cá nhân"}
            >
              <form key={JSON.stringify(resource.data)} onSubmit={save}>
                <fieldset disabled={action.busy} className="stack">
                  {contact ? (
                    <>
                      <Field
                        name="email"
                        label="Email mới"
                        type="email"
                        placeholder={resource.data.email ?? "Chưa có email"}
                        maxLength={254}
                        error={action.fields.email}
                      />
                      <Field
                        name="phone"
                        label="Số điện thoại mới"
                        placeholder={resource.data.phone ?? "+84901234567"}
                        pattern="\+[1-9][0-9]{7,14}"
                        error={action.fields.phone}
                      />
                      <Notice>
                        Thông tin đăng nhập hiện tại vẫn có hiệu lực đến khi bạn
                        xác minh liên hệ mới.
                      </Notice>
                    </>
                  ) : (
                    <>
                      <Field
                        name="fullName"
                        label="Họ và tên"
                        defaultValue={resource.data.fullName}
                        minLength={2}
                        maxLength={120}
                        required
                        error={action.fields.fullName}
                      />
                      <Field
                        name="dateOfBirth"
                        label="Ngày sinh"
                        type="date"
                        max={new Date().toISOString().slice(0, 10)}
                        defaultValue={resource.data.dateOfBirth ?? ""}
                        error={action.fields.dateOfBirth}
                      />
                      <Field
                        name="avatarUrl"
                        label="Đường dẫn ảnh đại diện"
                        type="url"
                        defaultValue={resource.data.avatarUrl ?? ""}
                        maxLength={2048}
                      />
                    </>
                  )}
                </fieldset>
                <Feedback action={action} />
                <div className="form-footer">
                  <Button busy={action.busy}>Lưu thay đổi</Button>
                </div>
              </form>
            </Panel>
            <div className="stack">
              <Panel title="Tài khoản đã xác minh">
                <p className="small">
                  {resource.data.email ?? resource.data.phone}
                </p>
                <p className="small muted" style={{ marginTop: 12 }}>
                  {resource.data.roles.map(label).join(" · ")}
                </p>
                <p
                  className="small"
                  style={{ marginTop: 20, color: "var(--brand)" }}
                >
                  <ShieldCheck
                    size={17}
                    style={{ display: "inline", marginRight: 8 }}
                  />
                  {label(resource.data.status)}
                </p>
              </Panel>
              {challenges.data?.map((c) => (
                <Panel
                  key={c.id}
                  title={`Xác minh ${c.channel === "EMAIL" ? "email" : "số điện thoại"}`}
                >
                  <p className="small muted">{c.recipient}</p>
                  <form
                    className="stack"
                    style={{ marginTop: 20 }}
                    onSubmit={(e) => {
                      e.preventDefault();
                      const f = new FormData(e.currentTarget);
                      void action.run(
                        () =>
                          authApi.verify({
                            challengeId: c.id,
                            code: String(f.get("code")),
                          }),
                        () => {
                          resource.reload();
                          challenges.reload();
                          void refreshSession().catch(() => {});
                          action.setMessage(
                            "Đã xác minh thông tin liên hệ mới.",
                          );
                        },
                      );
                    }}
                  >
                    <Field
                      name="code"
                      label="Mã xác minh"
                      pattern="[0-9]{6}"
                      maxLength={6}
                      inputMode="numeric"
                      required
                    />
                    <Button busy={action.busy}>Xác minh</Button>
                    <Button
                      type="button"
                      variant="ghost"
                      busy={action.busy}
                      onClick={() => {
                        void action.run(
                          () =>
                            api(
                              `/users/me/contact-challenges/${c.channel}/resend`,
                              { method: "POST" },
                            ),
                          () => {
                            challenges.reload();
                            action.setMessage("Đã gửi lại mã xác minh.");
                          },
                        );
                      }}
                    >
                      Gửi lại mã
                    </Button>
                  </form>
                </Panel>
              ))}
            </div>
          </div>
        )}
      </ResourceState>
    </div>
  );
}
