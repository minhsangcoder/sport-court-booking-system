// Browser-side substitute for the Google SDK; application components run unchanged.
export function googleMapsFixture() {
  class LatLng {
    constructor(point) {
      this.latitude = typeof point.lat === "function" ? point.lat() : point.lat;
      this.longitude = typeof point.lng === "function" ? point.lng() : point.lng;
    }
    lat() { return this.latitude; }
    lng() { return this.longitude; }
  }
  class Bounds {
    points = [];
    extend(point) { this.points.push(point); }
    getCenter() {
      return new LatLng({ lat: this.points.reduce((sum, point) => sum + point.lat, 0) / this.points.length,
        lng: this.points.reduce((sum, point) => sum + point.lng, 0) / this.points.length });
    }
  }
  class MapView {
    listeners = new Map();
    pins = new Set();
    constructor(host, options) {
      this.host = host;
      this.center = new LatLng(options.center);
      this.zoom = options.zoom;
      this.canvas = document.createElement("div");
      this.canvas.className = "gm-style";
      Object.assign(this.canvas.style, { position: "relative", width: "100%", height: "100%", background: "#e7ece8" });
      host.replaceChildren(this.canvas);
      this.canvas.addEventListener("click", (event) => {
        const box = this.canvas.getBoundingClientRect();
        this.emit("click", { latLng: new LatLng({ lat: this.center.lat() - (event.clientY - box.y - box.height / 2) / this.scale,
          lng: this.center.lng() + (event.clientX - box.x - box.width / 2) / this.scale }) });
      });
    }
    get scale() { return 2 ** this.zoom / 8; }
    addListener(name, handler) {
      if (!this.listeners.has(name)) this.listeners.set(name, new Set());
      this.listeners.get(name).add(handler);
      return { remove: () => this.listeners.get(name)?.delete(handler) };
    }
    emit(name, value) { for (const handler of this.listeners.get(name) ?? []) handler(value); }
    panTo(point) { this.setCenter(point); }
    setCenter(point) { this.center = new LatLng(point); this.draw(); }
    setZoom(zoom) { this.zoom = zoom; this.draw(); }
    getZoom() { return this.zoom; }
    fitBounds(bounds) { this.setCenter(bounds.getCenter()); this.setZoom(14); queueMicrotask(() => this.emit("idle")); }
    draw() { for (const pin of this.pins) pin.draw(); }
    unbindAll() { this.listeners.clear(); }
  }
  class AdvancedMarker extends HTMLElement {
    constructor(options) {
      super();
      this.title = options.title;
      this.gmpClickable = options.gmpClickable;
      this.gmpDraggable = options.gmpDraggable;
      this.textContent = "📍";
      Object.assign(this.style, { position: "absolute", width: "28px", height: "40px", fontSize: "28px", cursor: "pointer" });
      this.position = options.position;
      this.map = options.map;
      this.addEventListener("click", (event) => {
        event.stopPropagation();
        if (this.gmpClickable) this.dispatchEvent(new Event("gmp-click"));
      });
      let start;
      this.addEventListener("pointerdown", (event) => {
        if (!this.gmpDraggable) return;
        start = { x: event.clientX, y: event.clientY };
        this.setPointerCapture(event.pointerId);
      });
      this.addEventListener("pointerup", (event) => {
        if (!start || !this.map) return;
        const point = new LatLng(this.position);
        this.position = { lat: point.lat() - (event.clientY - start.y) / this.map.scale,
          lng: point.lng() + (event.clientX - start.x) / this.map.scale };
        start = null;
        this.dispatchEvent(new Event("gmp-dragend"));
      });
    }
    set position(value) { this.point = value; this.draw(); }
    get position() { return this.point; }
    set map(value) {
      this.view?.pins.delete(this);
      this.view = value;
      if (value && this.position) { value.pins.add(this); value.canvas.append(this); this.draw(); }
      else this.remove();
    }
    get map() { return this.view; }
    draw() {
      if (!this.view || !this.point) return;
      const point = new LatLng(this.point);
      this.style.left = `calc(50% + ${(point.lng() - this.view.center.lng()) * this.view.scale - 14}px)`;
      this.style.top = `calc(50% - ${(point.lat() - this.view.center.lat()) * this.view.scale + 40}px)`;
    }
  }
  customElements.define("gmp-advanced-marker", AdvancedMarker);
  class InfoWindow {
    setContent(content) { this.content = content; }
    open({ map }) {
      this.close();
      this.element = document.createElement("div");
      Object.assign(this.element.style, { position: "absolute", top: "5px", left: "5px", background: "white", padding: "12px", zIndex: "1" });
      this.element.append(this.content);
      map.canvas.append(this.element);
    }
    close() { this.element?.remove(); }
  }
  const maps = { Map: MapView, InfoWindow, LatLng, LatLngBounds: Bounds };
  window.google = { maps: { ...maps,
    event: { addListenerOnce(target, name, handler) {
      const listener = target.addListener(name, (value) => { listener.remove(); handler(value); });
      return listener;
    } },
    importLibrary: async (name) => name === "maps" ? maps : name === "marker" ? { AdvancedMarkerElement: AdvancedMarker } : {},
  } };
}
