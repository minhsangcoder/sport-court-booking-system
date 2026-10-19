import { Suspense } from "react";
import { notFound } from "next/navigation";
import { RouteView } from "@/rebuilt/components/route-view";
import { isFrontendRoute } from "@/rebuilt/lib/routes";
export default async function Page({
  params,
}: {
  params: Promise<{ path?: string[] }>;
}) {
  const { path = [] } = await params;
  if (!isFrontendRoute("/" + path.join("/"))) notFound();
  return (
    <Suspense
      fallback={
        <div className="loading-state" role="status">
          Đang chuẩn bị không gian của bạn…
        </div>
      }
    >
      <RouteView parts={path} />
    </Suspense>
  );
}
