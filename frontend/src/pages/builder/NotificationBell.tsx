import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, Bell, CheckCircle2, MessageCircleQuestion } from 'lucide-react';
import { builderNotifications, type BuilderNotification } from '../../api/builder';
import { ApiError } from '../../api/http';

/** How often the App Builder is asked what needs the person signed in. */
export const NOTIFY_POLL_MS = 15000;
/** Fired when a change request has been looked at, so the bell catches up at once. */
export const BUILDER_SEEN = 'wms:builder-seen';

const ICON = {
  FINISHED: CheckCircle2,
  QUESTIONS: MessageCircleQuestion,
  PROBLEM: AlertTriangle,
};

const HEADLINE = {
  FINISHED: 'Finished',
  QUESTIONS: 'Needs your answers',
  PROBLEM: 'Stopped',
};

function canNotify(): boolean {
  return typeof window !== 'undefined' && 'Notification' in window;
}

/**
 * The bell in the top bar: the change requests you asked for that need you, wherever you are in the WMS. It can also
 * tell you on this computer, while a WMS page is open, when the agent finishes or has questions.
 */
export function NotificationBell() {
  const navigate = useNavigate();
  const [items, setItems] = useState<BuilderNotification[]>([]);
  const [off, setOff] = useState(false);
  const [open, setOpen] = useState(false);
  const [permission, setPermission] = useState<NotificationPermission | 'unsupported'>(() =>
    canNotify() ? Notification.permission : 'unsupported',
  );
  /** What has been seen in this tab, so only news is announced. Empty until the first answer. */
  const known = useRef<Set<string> | null>(null);
  const panel = useRef<HTMLDivElement>(null);

  const load = useCallback(() => {
    builderNotifications()
      .then((found) => {
        const keys = found.map((n) => `${n.id}:${n.kind}:${n.at}`);
        if (known.current && canNotify() && Notification.permission === 'granted') {
          found
            .filter((_, i) => !known.current!.has(keys[i]))
            .forEach((n) => {
              const shown = new Notification(`${n.number} · ${HEADLINE[n.kind]}`, { body: `${n.title}\n${n.message}`, tag: n.id });
              shown.onclick = () => {
                window.focus();
                navigate(`/builder/${n.id}`);
                shown.close();
              };
            });
        }
        known.current = new Set([...(known.current ?? []), ...keys]);
        setItems(found);
      })
      .catch((error) => {
        // Where the App Builder is off, or this person may not use it, there is nothing to show.
        if (error instanceof ApiError && (error.status === 404 || error.status === 403)) setOff(true);
      });
  }, [navigate]);

  useEffect(() => {
    if (off) return;
    load();
    const handle = setInterval(load, NOTIFY_POLL_MS);
    window.addEventListener(BUILDER_SEEN, load);
    window.addEventListener('focus', load);
    return () => {
      clearInterval(handle);
      window.removeEventListener(BUILDER_SEEN, load);
      window.removeEventListener('focus', load);
    };
  }, [load, off]);

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent ? event.key === 'Escape' : !panel.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', close);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', close);
    };
  }, [open]);

  if (off) return null;

  return (
    <div className="bell" ref={panel}>
      <button
        type="button"
        className="icon-button bell__button"
        aria-label={items.length === 0 ? 'App Builder: nothing needs you' : `App Builder: ${items.length} need you`}
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <Bell size={18} aria-hidden="true" />
        {items.length > 0 && <span className="bell__count">{items.length}</span>}
      </button>
      {open && (
        <div className="bell__panel" role="dialog" aria-label="App Builder notifications">
          <p className="bell__title">App Builder</p>
          {items.length === 0 ? (
            <p className="muted bell__empty">Nothing needs you. You'll see here when the agent finishes or has questions.</p>
          ) : (
            <ul className="bell__list">
              {items.map((n) => {
                const Icon = ICON[n.kind];
                return (
                  <li key={n.id}>
                    <Link to={`/builder/${n.id}`} className={`bell__item bell__item--${n.kind.toLowerCase()}`} onClick={() => setOpen(false)}>
                      <Icon size={18} aria-hidden="true" />
                      <span className="bell__text">
                        <span className="bell__head">
                          {HEADLINE[n.kind]} · {n.number}
                        </span>
                        <span className="bell__change">{n.title}</span>
                        <span className="muted bell__message">{n.message}</span>
                      </span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          )}
          {permission === 'default' && (
            <button
              type="button"
              className="link-button link-button--small bell__allow"
              onClick={() => void Notification.requestPermission().then(setPermission)}
            >
              Also tell me on this computer
            </button>
          )}
          {permission === 'denied' && (
            <p className="muted bell__hint">Notifications on this computer are blocked in the browser's site settings.</p>
          )}
        </div>
      )}
    </div>
  );
}
