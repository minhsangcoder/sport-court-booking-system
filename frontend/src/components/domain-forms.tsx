"use client";
import type { Facility, Legal, Category } from "@/rebuilt/lib/domain";
import { weekdays } from "@/rebuilt/lib/domain";
import { useResource } from "@/rebuilt/lib/hooks";
import { Field, Notice, ResourceState, Select, TextArea } from "./ui";
import { LocationPicker } from "./location-picker";
export const legalFields: [keyof Legal, string, number][] = [
  ["representativeName", "Người đại diện", 180],
  ["identityNumber", "Số CCCD / giấy tờ định danh", 50],
  ["businessName", "Tên đơn vị kinh doanh", 180],
  ["businessLicense", "Giấy phép kinh doanh", 100],
  ["taxCode", "Mã số thuế", 50],
  ["bankName", "Ngân hàng", 100],
  ["bankAccountHolder", "Chủ tài khoản ngân hàng", 100],
  ["bankAccountNumber", "Số tài khoản ngân hàng", 50],
];
export function LegalFields({
  value,
  errors = {},
}: {
  value?: Legal;
  errors?: Record<string, string>;
}) {
  return (
    <>
      {legalFields.map(([name, caption, max]) => (
        <Field
          key={name}
          name={name}
          label={caption}
          defaultValue={value?.[name] ?? ""}
          maxLength={max}
          required={!["businessLicense", "taxCode"].includes(name)}
          error={
            errors[name] ??
            errors["legal." + name] ??
            errors["ownerApplication.legal." + name]
          }
        />
      ))}
    </>
  );
}
export function legalFrom(f: FormData) {
  return Object.fromEntries(
    legalFields.map(([name]) => [
      name,
      String(f.get(name) ?? "").trim() || null,
    ]),
  );
}
export function FacilityFields({
  value,
  nameField = "name",
  coordinatesRequired = false,
  errors = {},
}: {
  value?: Facility;
  nameField?: string;
  coordinatesRequired?: boolean;
  errors?: Record<string, string>;
}) {
  return (
    <>
      <Field
        name={nameField}
        label="Tên cơ sở"
        defaultValue={value?.name}
        maxLength={180}
        required
        error={
          errors.name ??
          errors["facility.name"] ??
          errors["ownerApplication.facility.name"]
        }
      />
      <Field
        name="facilityPhone"
        label="Số điện thoại cơ sở"
        defaultValue={value?.phone}
        maxLength={30}
        required
        error={errors.phone ?? errors["facility.phone"]}
      />
      <Field
        name="contactEmail"
        label="Email liên hệ cơ sở"
        type="email"
        defaultValue={value?.contactEmail ?? ""}
        maxLength={254}
        error={errors.contactEmail}
      />
      <Field
        name="province"
        label="Tỉnh / Thành phố"
        defaultValue={value?.province}
        maxLength={100}
        required
        error={errors.province}
      />
      <Field
        name="district"
        label="Quận / Huyện"
        defaultValue={value?.district}
        maxLength={100}
        required
        error={errors.district}
      />
      <Field
        name="ward"
        label="Phường / Xã"
        defaultValue={value?.ward}
        maxLength={100}
        required
        error={errors.ward}
      />
      <Field
        name="addressLine"
        label="Địa chỉ chi tiết"
        defaultValue={value?.addressLine}
        maxLength={500}
        required
        error={errors.addressLine}
      />
      <Field
        name="timezone"
        label="Múi giờ (IANA)"
        defaultValue={value?.timezone ?? "Asia/Ho_Chi_Minh"}
        maxLength={80}
        required
        error={errors.timezone}
      />
      <LocationPicker
        latitude={value?.latitude}
        longitude={value?.longitude}
        required={coordinatesRequired}
      />
      <TextArea
        name="description"
        label="Giới thiệu cơ sở"
        defaultValue={value?.description ?? ""}
        error={errors.description}
      />
      <Field
        name="amenities"
        label="Tiện ích"
        defaultValue={value?.amenities.join(", ")}
        hint="Ngăn cách bằng dấu phẩy. Ví dụ: Bãi đỗ xe, Phòng thay đồ, Wi-Fi."
      />
    </>
  );
}
export function facilityFrom(f: FormData, name = "name") {
  const v = (key: string) => String(f.get(key) ?? "").trim();
  const lat = v("latitude"),
    lon = v("longitude");
  if (Boolean(lat) !== Boolean(lon))
    throw new Error("Nhập đủ vĩ độ và kinh độ, hoặc để trống cả hai.");
  const amenities = v("amenities")
    .split(",")
    .map((x) => x.trim())
    .filter(Boolean);
  if (amenities.length > 30 || amenities.some((x) => x.length > 100))
    throw new Error("Tối đa 30 tiện ích, mỗi tiện ích không quá 100 ký tự.");
  return {
    name: v(name),
    phone: v("facilityPhone"),
    addressLine: v("addressLine"),
    province: v("province"),
    district: v("district"),
    ward: v("ward"),
    description: v("description"),
    timezone: v("timezone"),
    latitude: lat ? Number(lat) : null,
    longitude: lon ? Number(lon) : null,
    amenities,
    contactEmail: v("contactEmail") || null,
  };
}
export function UploadField({
  name = "file",
  label,
  image = false,
  required = true,
}: {
  name?: string;
  label: string;
  image?: boolean;
  required?: boolean;
}) {
  return (
    <Field
      name={name}
      label={label}
      type="file"
      accept={image ? "image/png,image/jpeg" : ".pdf,.png,.jpg,.jpeg"}
      required={required}
      hint={
        image
          ? "JPG hoặc PNG, tối đa 10 MB."
          : "PDF, JPG hoặc PNG, tối đa 10 MB."
      }
    />
  );
}
export function validateFile(file: FormDataEntryValue | null, image = false) {
  if (!(file instanceof File) || !file.size)
    throw new Error("Chọn tệp cần tải lên.");
  if (file.size > 10 * 1024 * 1024) throw new Error("Tệp vượt quá 10 MB.");
  if (
    !(
      image
        ? ["image/png", "image/jpeg"]
        : ["application/pdf", "image/png", "image/jpeg"]
    ).includes(file.type)
  )
    throw new Error("Định dạng tệp chưa được hỗ trợ.");
  return file;
}
export function SignupSetup() {
  const categories = useResource<Category[]>("/sport-categories");
  return (
    <>
      <Field name="courtCode" label="Mã sân đầu tiên" required maxLength={50} />
      <Field
        name="courtName"
        label="Tên sân đầu tiên"
        required
        maxLength={180}
      />
      <Select name="sportCategoryId" label="Bộ môn" required defaultValue="">
        <option value="">Chọn bộ môn</option>
        {categories.data
          ?.filter((c) => c.active)
          .map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
      </Select>
      <Field
        name="pricePerSlot"
        label="Giá mỗi khung giờ (VND)"
        type="number"
        min={1}
        max={9999999999}
        step={1}
        required
      />
      <Field
        name="opensAt"
        label="Giờ mở cửa"
        type="time"
        defaultValue="06:00"
        required
      />
      <Field
        name="closesAt"
        label="Giờ đóng cửa"
        type="time"
        defaultValue="22:00"
        required
      />
      <Field
        name="slotMinutes"
        label="Độ dài khung giờ (phút)"
        type="number"
        min={5}
        max={720}
        defaultValue={60}
        required
      />
      <fieldset className="span-2">
        <legend className="small" style={{ marginBottom: 14 }}>
          Các ngày mở cửa
        </legend>
        <div className="actions">
          {weekdays.map((day, i) => (
            <label key={day} className="small actions">
              <input type="checkbox" name="days" value={i + 1} defaultChecked />
              {day}
            </label>
          ))}
        </div>
      </fieldset>
      {categories.error && (
        <div className="span-2">
          <ResourceState resource={categories}>
            <></>
          </ResourceState>
        </div>
      )}
    </>
  );
}
export function setupFrom(f: FormData) {
  const val = (k: string) => String(f.get(k) ?? "");
  const days = f.getAll("days").map(Number),
    opensAt = val("opensAt"),
    closesAt = val("closesAt"),
    slotMinutes = Number(val("slotMinutes"));
  if (!days.length) throw new Error("Chọn ít nhất một ngày mở cửa.");
  const mins = (x: string) =>
    Number(x.slice(0, 2)) * 60 + Number(x.slice(3, 5));
  if (
    opensAt >= closesAt ||
    (mins(closesAt) - mins(opensAt)) % slotMinutes !== 0
  )
    throw new Error(
      "Giờ đóng cửa phải sau giờ mở cửa; khoảng mở cửa phải chia hết cho độ dài khung giờ.",
    );
  return {
    courtCode: val("courtCode"),
    courtName: val("courtName"),
    sportCategoryId: val("sportCategoryId"),
    days,
    opensAt,
    closesAt,
    slotMinutes,
    pricePerSlot: Number(val("pricePerSlot")),
  };
}
export function ApplicationDisclosure() {
  return (
    <Notice>
      Hồ sơ pháp lý chỉ dành cho bạn và quản trị viên thẩm định. Quyền chủ sân được cấp
      sau khi hồ sơ được phê duyệt.
    </Notice>
  );
}
