import { useEffect, useId, useRef, useState } from 'react';
import { CheckCircle2, CircleDashed, ClipboardList, FileText, XCircle, X } from 'lucide-react';

export type CheckResult = 'passed' | 'failed' | 'not_checked';

/** What the agent says it built, as it handed it over at the end of its work. */
export interface ChangeReport {
  headline: string;
  changes: { area: string; what: string }[];
  tryIt: string[];
  checklist: { item: string; result: CheckResult; note: string | null }[];
  leftOut: string[];
}

const text = (value: unknown) => (typeof value === 'string' ? value : '');
const texts = (value: unknown) => (Array.isArray(value) ? value.map(text).filter((t) => t.trim() !== '') : []);
const objects = (value: unknown) =>
  Array.isArray(value) ? value.filter((v): v is Record<string, unknown> => typeof v === 'object' && v !== null) : [];

/** The summary in a report_change call, read carefully: it is whatever the agent sent. */
export function readReport(input: Record<string, unknown>): ChangeReport {
  return {
    headline: text(input.headline),
    changes: objects(input.changes).map((c) => ({ area: text(c.area), what: text(c.what) })),
    tryIt: texts(input.try_it),
    checklist: objects(input.checklist).map((c) => ({
      item: text(c.item),
      result: c.result === 'passed' || c.result === 'failed' ? c.result : 'not_checked',
      note: text(c.note) || null,
    })),
    leftOut: texts(input.left_out),
  };
}

const RESULT: Record<CheckResult, { label: string; className: string }> = {
  passed: { label: 'Passed', className: 'check-result--passed' },
  failed: { label: 'Failed', className: 'check-result--failed' },
  not_checked: { label: 'Not checked', className: 'check-result--unchecked' },
};

function ResultIcon({ result }: { result: CheckResult }) {
  if (result === 'passed') return <CheckCircle2 size={18} aria-hidden="true" />;
  if (result === 'failed') return <XCircle size={18} aria-hidden="true" />;
  return <CircleDashed size={18} aria-hidden="true" />;
}

/** How the checklist came out, like "9 passed, 1 not checked". */
export function tally(report: ChangeReport): string {
  return (['passed', 'failed', 'not_checked'] as const)
    .map((result) => [result, report.checklist.filter((c) => c.result === result).length] as const)
    .filter(([, n]) => n > 0)
    .map(([result, n]) => `${n} ${RESULT[result].label.toLowerCase()}`)
    .join(', ');
}

/** The summary in the conversation: its headline and how the checks came out, opening the full summary. */
export function SummaryCard({ report, title, number }: { report: ChangeReport; title: string; number: string }) {
  const [open, setOpen] = useState(false);
  const failed = report.checklist.some((c) => c.result === 'failed');
  return (
    <div className={`summary-card${failed ? ' summary-card--failed' : ''}`}>
      <p className="summary-card__head">
        <ClipboardList size={18} aria-hidden="true" />
        Change summary
      </p>
      <p>{report.headline}</p>
      <p className="muted summary-card__facts">
        {report.changes.length} {report.changes.length === 1 ? 'part' : 'parts'} of the WMS changed
        {report.checklist.length > 0 && ` · ${tally(report)}`}
      </p>
      <div>
        <button type="button" className="button button--primary" onClick={() => setOpen(true)}>
          <FileText size={16} aria-hidden="true" />
          Open summary
        </button>
      </div>
      {open && <SummaryPage report={report} title={title} number={number} onClose={() => setOpen(false)} />}
    </div>
  );
}

/** The summary as a page of its own, for the people who asked for the change. */
function SummaryPage({ report, title, number, onClose }: { report: ChangeReport; title: string; number: string; onClose: () => void }) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    panel.current?.querySelector<HTMLElement>('button')?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCloseRef.current();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      before?.focus?.();
    };
  }, []);

  return (
    <div className="modal-layer">
      <div className="drawer-backdrop" onClick={onClose} aria-hidden="true" />
      <div className="modal modal--wide" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={panel}>
        <header className="modal__header">
          <div>
            <p className="muted summary__number">{number} · Change summary</p>
            <h2 id={titleId}>{title}</h2>
          </div>
          <button type="button" className="icon-button" aria-label="Close" onClick={onClose}>
            <X size={18} aria-hidden="true" />
          </button>
        </header>
        <div className="modal__body summary">
          <p className="summary__headline">{report.headline}</p>

          {report.checklist.length > 0 && (
            <div className="summary__tally" aria-label="Checks">
              {(['passed', 'failed', 'not_checked'] as const).map((result) => {
                const n = report.checklist.filter((c) => c.result === result).length;
                return n === 0 ? null : (
                  <span key={result} className={`check-result ${RESULT[result].className}`}>
                    <ResultIcon result={result} /> {n} {RESULT[result].label.toLowerCase()}
                  </span>
                );
              })}
            </div>
          )}

          <section aria-labelledby={`${titleId}-changed`}>
            <h3 id={`${titleId}-changed`}>What changed</h3>
            <div className="summary__changes">
              {report.changes.map((change, i) => (
                <div key={i} className="summary__change">
                  <p className="summary__area">{change.area}</p>
                  <p>{change.what}</p>
                </div>
              ))}
            </div>
          </section>

          {report.tryIt.length > 0 && (
            <section aria-labelledby={`${titleId}-try`}>
              <h3 id={`${titleId}-try`}>How to try it</h3>
              <ol className="summary__steps">
                {report.tryIt.map((step, i) => (
                  <li key={i}>{step}</li>
                ))}
              </ol>
            </section>
          )}

          {report.checklist.length > 0 && (
            <section aria-labelledby={`${titleId}-check`}>
              <h3 id={`${titleId}-check`}>Checklist</h3>
              <ul className="summary__checklist">
                {report.checklist.map((check, i) => (
                  <li key={i} className={`check-result ${RESULT[check.result].className}`}>
                    <ResultIcon result={check.result} />
                    <span className="summary__check">
                      <span>{check.item}</span>
                      {check.note && <span className="muted">{check.note}</span>}
                    </span>
                    <span className="summary__result">{RESULT[check.result].label}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {report.leftOut.length > 0 && (
            <section aria-labelledby={`${titleId}-out`}>
              <h3 id={`${titleId}-out`}>Not included</h3>
              <ul className="summary__out">
                {report.leftOut.map((thing, i) => (
                  <li key={i}>{thing}</li>
                ))}
              </ul>
            </section>
          )}
          <footer className="modal__footer">
            <span />
            <button type="button" className="button" onClick={onClose}>
              Close
            </button>
          </footer>
        </div>
      </div>
    </div>
  );
}
