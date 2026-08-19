import { useEffect, useState } from "react";

function currentRoute(defaultRoute: string): string {
  return window.location.hash.replace(/^#\/?/, "") || defaultRoute;
}

export function useHashRoute(defaultRoute: string): [string, (route: string) => void] {
  const [route, setRoute] = useState(() => currentRoute(defaultRoute));
  useEffect(() => {
    const update = () => setRoute(currentRoute(defaultRoute));
    window.addEventListener("hashchange", update);
    return () => window.removeEventListener("hashchange", update);
  }, [defaultRoute]);
  const navigate = (next: string) => {
    window.location.hash = `#/${next}`;
    setRoute(next);
  };
  return [route, navigate];
}
