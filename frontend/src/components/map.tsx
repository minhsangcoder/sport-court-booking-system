"use client";
import { useEffect, useRef, useState } from "react";
import { loadGoogleMaps } from "@/features/facility/google-maps";
import type { FacilityDiscoveryItem } from "@/rebuilt/lib/domain";
import { facilitySearchHref } from "@/rebuilt/lib/discovery";
import { Notice } from "./ui";

export function CourtMap({ items, query = new URLSearchParams() }: { items: FacilityDiscoveryItem[]; query?: URLSearchParams }) {
  const container = useRef<HTMLDivElement>(null),
    [failed, setFailed] = useState(false);
  const queryString = query.toString();
  useEffect(() => {
    let active = true;
    let map: google.maps.Map | undefined;
    let popup: google.maps.InfoWindow | undefined;
    const pins: google.maps.marker.AdvancedMarkerElement[] = [];
    const listeners: google.maps.MapsEventListener[] = [];
    const controller = new AbortController();
    const host = container.current;
    void loadGoogleMaps()
      .then(({ maps, marker }) => {
        if (!active || !container.current) return;
        const seen = new Set<string>();
        const points = items.filter(({ facility }) => {
          const { id, latitude, longitude } = facility;
          if (seen.has(id) || latitude == null || longitude == null ||
            !Number.isFinite(latitude) || !Number.isFinite(longitude) ||
            Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return false;
          seen.add(id);
          return true;
        });
        map = new maps.Map(container.current, {
          center: { lat: 16.0471, lng: 108.2068 },
          zoom: 5,
          mapId: "DEMO_MAP_ID",
          mapTypeControl: false,
          streetViewControl: false,
          clickableIcons: false,
          gestureHandling: "cooperative",
        });
        popup = new maps.InfoWindow();
        const bounds = new google.maps.LatLngBounds();
        const search = new URLSearchParams(queryString);
        for (const item of points) {
          const position = { lat: item.facility.latitude!, lng: item.facility.longitude! };
          bounds.extend(position);
          const content = document.createElement("div");
          const title = document.createElement("strong");
          title.textContent = item.facility.name;
          const address = document.createElement("p");
          address.textContent = item.facility.addressLine;
          const link = document.createElement("a");
          link.href = facilitySearchHref(item, search);
          link.textContent = "Xem sân trống →";
          content.append(title, address, link);
          const pin = new marker.AdvancedMarkerElement({
            map,
            position,
            title: item.facility.name,
            gmpClickable: true,
          });
          pins.push(pin);
          pin.addEventListener("gmp-click", () => {
            popup!.setContent(content);
            popup!.open({ map, anchor: pin });
          }, { signal: controller.signal });
        }
        if (points.length === 1) {
          map.setCenter(bounds.getCenter());
          map.setZoom(14);
        } else if (points.length > 1) {
          const instance = map;
          listeners.push(google.maps.event.addListenerOnce(instance, "idle", () => {
            if ((instance.getZoom() ?? 0) > 14) instance.setZoom(14);
          }));
          map.fitBounds(bounds, 45);
        }
        setFailed(false);
      })
      .catch(() => {
        if (active) setFailed(true);
      });
    return () => {
      active = false;
      controller.abort();
      for (const listener of listeners) listener.remove();
      for (const pin of pins) pin.map = null;
      popup?.close();
      map?.unbindAll();
      host?.replaceChildren();
    };
  }, [items, queryString]);
  return (
    <>
      {failed && (
        <Notice tone="warning">
          Không tải được Google Maps. Bạn vẫn có thể xem địa chỉ và đặt sân trong danh sách.
        </Notice>
      )}
      <div
        className="map-container"
        ref={container}
        role="region"
        aria-label="Bản đồ cơ sở trên Google Maps"
      />
    </>
  );
}
