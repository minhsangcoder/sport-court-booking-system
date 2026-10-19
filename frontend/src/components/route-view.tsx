"use client";
import type { ReactNode } from "react";
import { Guard } from "./shell";
import {
  Home,
  SearchPage,
  FacilityPage,
  AvailabilityPage,
} from "@/rebuilt/modules/discovery";
import { AuthPage, ProfilePage } from "@/rebuilt/modules/auth";
import {
  BookingsPage,
  BookingDetailPage,
  HoldPage,
  PaymentPage,
} from "@/rebuilt/modules/booking";
import { GroupsPage, GroupPage, JoinGroupPage } from "@/rebuilt/modules/groups";
import {
  TransferMarket,
  ListingPage,
  ListingForm,
  AcquisitionPage,
} from "@/rebuilt/modules/transfers";
import {
  OwnerApplyPage,
  ApplicationsPage,
  ApplicationPage,
  ApplicationFacility,
} from "@/rebuilt/modules/applications";
import {
  FacilityWorkspace,
  FacilitiesPage,
  FacilityOverview,
  FacilityForm,
  CourtsPage,
  CourtForm,
  MaintenancePage,
  StaffPage,
  DocumentsPage,
  ReviewPage,
  TransferPolicyPage,
} from "@/rebuilt/modules/facilities";
import {
  SchedulePage,
  SchedulePreview,
  PricingPage,
} from "@/rebuilt/modules/schedules";
import {
  OperationsPage,
  OperationBooking,
  CounterCourt,
  OwnerDashboard,
} from "@/rebuilt/modules/operations";
import { FacilityReport, AdminReports } from "@/rebuilt/modules/reports";
import {
  AccountsPage,
  AccountPage,
  AdminFacilities,
  AdminFacility,
  AdminBookings,
  AdminPayments,
  AdminPayment,
  SportsPage,
  DisputesPage,
} from "@/rebuilt/modules/admin";
function FacilityScreen({ parts }: { parts: string[] }) {
  switch (parts[0]) {
    case "edit":
      return <FacilityForm />;
    case "courts":
      return parts[1] === "new" ? (
        <CourtForm />
      ) : parts[1] ? (
        <CourtForm id={parts[1]} />
      ) : (
        <CourtsPage />
      );
    case "schedule":
      return parts[1] === "preview" ? <SchedulePreview /> : <SchedulePage />;
    case "pricing":
      return <PricingPage />;
    case "maintenance":
      return <MaintenancePage />;
    case "staff":
      return <StaffPage />;
    case "documents":
      return <DocumentsPage />;
    case "review":
      return <ReviewPage />;
    case "transfer-policy":
      return <TransferPolicyPage />;
    case "reports":
      return <FacilityReport />;
    default:
      return <FacilityOverview />;
  }
}
export function RouteView({ parts }: { parts: string[] }) {
  const [root, section, id, sub] = parts;
  let page: ReactNode;
  if (!root) return <Home />;
  if (
    [
      "login",
      "register",
      "verify",
      "forgot-password",
      "reset-password",
    ].includes(root)
  )
    return (
      <AuthPage
        mode={
          root === "forgot-password"
            ? "forgot"
            : root === "reset-password"
              ? "reset"
              : (root as "login" | "register" | "verify")
        }
      />
    );
  if (root === "search") return <SearchPage />;
  if (root === "facilities")
    return id === "courts" ? (
      <AvailabilityPage facilityId={section} courtId={sub} />
    ) : (
      <FacilityPage id={section} />
    );
  if (root === "transfers")
    return (
      <Guard roles={["CUSTOMER"]}>
        {section ? <ListingPage id={section} /> : <TransferMarket />}
      </Guard>
    );
  if (root === "customer") {
    switch (section) {
      case "bookings":
        page = id ? (
          sub === "transfer" ? (
            <ListingForm bookingId={id} />
          ) : (
            <BookingDetailPage id={id} />
          )
        ) : (
          <BookingsPage />
        );
        break;
      case "booking":
        page = <HoldPage id={sub} />;
        break;
      case "payments":
        page = <PaymentPage id={id} />;
        break;
      case "groups":
        page =
          id === "join" ? (
            <JoinGroupPage />
          ) : id ? (
            <GroupPage
              id={id}
              mode={
                sub === "split"
                  ? "split"
                  : sub === "invite"
                    ? "invite"
                    : "detail"
              }
            />
          ) : (
            <GroupsPage />
          );
        break;
      case "transfers":
        page =
          id === "acquisitions" ? (
            <AcquisitionPage id={sub} />
          ) : id ? (
            <ListingForm listingId={id} />
          ) : (
            <TransferMarket mine />
          );
        break;
      default:
        page = <ProfilePage contact={id === "contact"} />;
    }
    return (
      <Guard
        roles={
          ["profile", "payments", "booking"].includes(section)
            ? undefined
            : ["CUSTOMER"]
        }
      >
        {page}
      </Guard>
    );
  }
  if (root === "owner") {
    if (section === "apply")
      return (
        <Guard roles={["CUSTOMER", "OWNER"]}>
          <OwnerApplyPage />
        </Guard>
      );
    if (section === "application")
      return (
        <Guard roles={["CUSTOMER", "OWNER"]}>
          {!id ? (
            <ApplicationsPage />
          ) : sub === "facility" ? (
            <ApplicationFacility id={id}>
              <FacilityScreen parts={parts.slice(4)} />
            </ApplicationFacility>
          ) : (
            <ApplicationPage id={id} legal={sub === "legal"} />
          )}
        </Guard>
      );
    page =
      section === "facilities" ? (
        id === "new" ? (
          <>
            <FacilityForm create />
          </>
        ) : id ? (
          <FacilityWorkspace id={id}>
            <FacilityScreen parts={parts.slice(3)} />
          </FacilityWorkspace>
        ) : (
          <FacilitiesPage />
        )
      ) : section === "bookings" ? (
        id ? (
          <OperationBooking id={id} owner />
        ) : (
          <OperationsPage owner />
        )
      ) : (
        <OwnerDashboard />
      );
    return <Guard roles={["OWNER"]}>{page}</Guard>;
  }
  if (root === "staff") {
    page =
      section === "counter-booking" ? (
        id ? (
          <CounterCourt id={id} />
        ) : (
          <OperationsPage view="counter" />
        )
      ) : section === "schedule" ? (
        <OperationsPage view="schedule" />
      ) : id ? (
        <OperationBooking id={id} />
      ) : (
        <OperationsPage />
      );
    return <Guard roles={["STAFF"]}>{page}</Guard>;
  }
  if (root === "admin") {
    switch (section) {
      case "accounts":
        page = id ? <AccountPage id={id} /> : <AccountsPage />;
        break;
      case "owner-applications":
        page = id ? (
          <ApplicationPage id={id} admin />
        ) : (
          <ApplicationsPage admin />
        );
        break;
      case "facilities":
        page = id ? <AdminFacility id={id} /> : <AdminFacilities />;
        break;
      case "bookings":
        page =
          id === "reconciliation" ? (
            <AdminBookings reconciliation />
          ) : id ? (
            <BookingDetailPage id={id} admin />
          ) : (
            <AdminBookings />
          );
        break;
      case "payments":
        page = id ? <AdminPayment id={id} /> : <AdminPayments />;
        break;
      case "sports":
        page = <SportsPage />;
        break;
      case "disputes":
        page = <DisputesPage />;
        break;
      case "reports":
        page = <AdminReports />;
        break;
      default:
        page = <AdminReports dashboard />;
    }
    return <Guard roles={["ADMIN"]}>{page}</Guard>;
  }
  return null;
}
