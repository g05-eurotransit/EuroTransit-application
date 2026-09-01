import {
  createContext,
  useContext,
  useEffect,
  useState,
  type PropsWithChildren,
} from "react";
import { useAuth } from "react-oidc-context";
import {
  listNotifications,
  subscribeNotifications,
  type NotificationView,
} from "../api/notifications";

export function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString("it-IT", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function shortOrderRef(orderId: string): string {
  return orderId.slice(0, 8);
}

interface NotificationsContextValue {
  items: NotificationView[];
  live: boolean;
  degraded: boolean;
  unseenCount: number;
  markAllSeen: () => void;
}

const NotificationsContext = createContext<NotificationsContextValue>({
  items: [],
  live: false,
  degraded: false,
  unseenCount: 0,
  markAllSeen: () => {},
});

export function useNotifications(): NotificationsContextValue {
  return useContext(NotificationsContext);
}

export function NotificationsProvider({ children }: PropsWithChildren) {
  const auth = useAuth();
  const [items, setItems] = useState<NotificationView[]>([]);
  const [live, setLive] = useState(false);
  const [degraded, setDegraded] = useState(false);
  const [unseenCount, setUnseenCount] = useState(0);

  useEffect(() => {
    if (!auth.isAuthenticated) {
      setItems([]);
      setLive(false);
      setDegraded(false);
      setUnseenCount(0);
      return;
    }

    const ac = new AbortController();
    let historyLoaded = false;

    listNotifications(ac.signal)
      .then((history) => {
        historyLoaded = true;
        setItems(history);
      })
      .catch(() => setDegraded(true));

    const unsubscribe = subscribeNotifications({
      onOpen: () => {
        setLive(true);
        setDegraded(false);
      },
      onNotification: (n) => {
        setItems((prev) => (prev.some((p) => p.id === n.id) ? prev : [n, ...prev]));
        if (historyLoaded) setUnseenCount((c) => c + 1);
      },
      onError: () => {
        setLive(false);
        setDegraded(true);
      },
    });

    return () => {
      ac.abort();
      unsubscribe();
    };
  }, [auth.isAuthenticated]);

  function markAllSeen() {
    setUnseenCount(0);
  }

  return (
    <NotificationsContext.Provider value={{ items, live, degraded, unseenCount, markAllSeen }}>
      {children}
    </NotificationsContext.Provider>
  );
}
