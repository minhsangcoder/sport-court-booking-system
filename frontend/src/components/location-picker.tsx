"use client";
import { useEffect, useRef, useState } from "react";
import { MapPin } from "lucide-react";
import { loadGoogleMaps } from "@/features/facility/google-maps";
import { Button, Field, Notice } from "./ui";

function mapPosition(lat: string, lon: string): google.maps.LatLngLiteral | null {
  if (!lat.trim() || !lon.trim()) return null;
  const latitude = Number(lat), longitude = Number(lon);
  return Number.isFinite(latitude) && Math.abs(latitude) <= 90 &&
    Number.isFinite(longitude) && Math.abs(longitude) <= 180
    ? { lat: latitude, lng: longitude }
    : null;
}
export function LocationPicker({
  latitude,
  longitude,
  required = false,
}: {
  latitude?: number | null;
  longitude?: number | null;
  required?: boolean;
}) {
  const [lat, setLat] = useState(
      latitude === null || latitude === undefined ? "" : String(latitude),
    ),
    [lon, setLon] = useState(
      longitude === null || longitude === undefined ? "" : String(longitude),
    ),
    [open, setOpen] = useState(false),
    [loading, setLoading] = useState(false),
    [error, setError] = useState("");
  const container = useRef<HTMLDivElement>(null),
    position = useRef({ lat, lon }),
    map = useRef<google.maps.Map | null>(null),
    marker = useRef<google.maps.marker.AdvancedMarkerElement | null>(null);
  useEffect(() => {
    position.current = { lat, lon };
    if (!map.current || !marker.current) return;
    const selected = mapPosition(lat, lon);
    marker.current.position = selected;
    marker.current.map = selected ? map.current : null;
    if (selected) map.current.panTo(selected);
  }, [lat, lon]);
  useEffect(() => {
    if (!open) return;
    let active = true;
    let instance: google.maps.Map | undefined;
    let pin: google.maps.marker.AdvancedMarkerElement | undefined;
    const listeners: google.maps.MapsEventListener[] = [];
    const controller = new AbortController();
    const host = container.current;
    void loadGoogleMaps()
      .then(({ maps, marker: markerLibrary }) => {
        if (!active || !container.current) return;
        const p = position.current;
        const selected = mapPosition(p.lat, p.lon);
        instance = new maps.Map(container.current, {
          center: selected ?? { lat: 16.0471, lng: 108.2068 },
          zoom: selected ? 15 : 5,
          mapId: "DEMO_MAP_ID",
          mapTypeControl: false,
          streetViewControl: false,
          clickableIcons: false,
          gestureHandling: "cooperative",
        });
        pin = new markerLibrary.AdvancedMarkerElement({
          map: selected ? instance : null,
          position: selected,
          gmpDraggable: true,
          title: "Kéo để chỉnh vị trí cơ sở",
        });
        map.current = instance;
        marker.current = pin;
        const selectPosition = (point: google.maps.LatLng) => {
          if (!active) return;
          setLat(point.lat().toFixed(7));
          setLon(point.lng().toFixed(7));
        };
        listeners.push(instance.addListener("click", (event: google.maps.MapMouseEvent) => {
          if (event.latLng) selectPosition(event.latLng);
        }));
        pin.addEventListener("gmp-dragend", () => {
          if (pin?.position) selectPosition(new google.maps.LatLng(pin.position));
        }, { signal: controller.signal });
        setLoading(false);
      })
      .catch(() => {
        if (!active) return;
        setLoading(false);
        setError("Không tải được Google Maps. Bạn vẫn có thể nhập tọa độ trực tiếp.");
      });
    return () => {
      active = false;
      controller.abort();
      for (const listener of listeners) listener.remove();
      if (pin) pin.map = null;
      instance?.unbindAll();
      map.current = null;
      marker.current = null;
      host?.replaceChildren();
    };
  }, [open]);
  return (
    <>
      <Field
        name="latitude"
        label="Vĩ độ"
        type="number"
        min={-90}
        max={90}
        step="any"
        value={lat}
        onChange={(e) => setLat(e.target.value)}
        required={required}
      />
      <Field
        name="longitude"
        label="Kinh độ"
        type="number"
        min={-180}
        max={180}
        step="any"
        value={lon}
        onChange={(e) => setLon(e.target.value)}
        required={required}
      />
      <div className="span-2">
        <Button
          type="button"
          variant="secondary"
          onClick={() => {
            setError("");
            setLoading(!open);
            setOpen(!open);
          }}
        >
          <MapPin size={15} />
          {open ? "Ẩn bản đồ" : "Chọn vị trí trên bản đồ"}
        </Button>
        {open && (
          <>
            <p className="small muted" style={{ margin: "12px 0" }}>
              Nhấp vào Google Maps để điền tọa độ, hoặc kéo ghim để chỉnh vị trí cơ sở.
            </p>
            <div
              ref={container}
              className="map-container"
              style={{ height: 320 }}
              role="region"
              aria-label="Chọn vị trí cơ sở trên Google Maps"
            />
            {loading && <p role="status" className="small muted">Đang tải Google Maps…</p>}
          </>
        )}
        {error && <Notice tone="warning">{error}</Notice>}
      </div>
    </>
  );
}
