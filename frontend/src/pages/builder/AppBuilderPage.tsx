import { useCallback, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Archive, CheckCircle2, Hammer, ExternalLink, MessageCircleQuestion, Play, Plus, RotateCcw, Send, Square } from 'lucide-react';
import {
  acceptChange,
  answerQuestions,
  discardChange,
  reopenChange,
  builderStatus,
  getChange,
  listChanges,
  markSeen,
  sayToAgent,
  startChange,
  startPreview,
  stopAgent,
  stopPreview,
  type Block,
  type BuilderStatus,
  type ChangeDetail,
  type ChangeSummary,
  type Entry,
} from '../../api/builder';
import { errorMessage } from '../../api/http';
import { QuestionsModal } from './QuestionsModal';
import { BUILDER_SEEN } from './NotificationBell';
import { RichText } from './RichText';
import { readReport, SummaryCard } from './ChangeSummary';

const DEV_MODE = 'wms.builder.developerMode';

/** Developer mode is a choice remembered on this computer; it is off until a developer turns it on. */
function useDeveloperMode(): [boolean, (on: boolean) => void] {
  const [on, setOn] = useState(() => {
    try {
      return window.localStorage.getItem(DEV_MODE) === 'on';
    } catch {
      return false;
    }
  });
  const set = (value: boolean) => {
    setOn(value);
    try {
      window.localStorage.setItem(DEV_MODE, value ? 'on' : 'off');
    } catch {
      // Not remembered; it still applies now.
    }
  };
  return [on, set];
}

/** How often a change request the agent is working on is looked at again. */
export const POLL_MS = 1500;

/**
 * The App Builder: change requests on the left, and the conversation with the agent about the one open on the right.
 * People describe what they want; the agent asks what it needs to know, builds it, and says what it did.
 */
export function AppBuilderPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [changes, setChanges] = useState<ChangeSummary[] | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [listKey, setListKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    listChanges(controller.signal)
      .then((found) => {
        setChanges(found);
        setListError(null);
      })
      .catch((error) => {
        if (!controller.signal.aborted) setListError(errorMessage(error));
      });
    return () => controller.abort();
  }, [listKey]);

  const refreshList = useCallback(() => setListKey((k) => k + 1), []);
  const [status, setStatus] = useState<BuilderStatus | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    builderStatus(controller.signal)
      .then(setStatus)
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  return (
    <div className="page page--wide">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Administration</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">App Builder</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>App Builder</h1>
          <p className="muted">Describe a change to the WMS. The agent asks what it needs to know, builds it, and tests it.</p>
        </div>
        <button type="button" className="button button--primary" onClick={() => navigate('/builder')}>
          <Plus size={16} aria-hidden="true" />
          New change
        </button>
      </header>

      <div className="builder">
        <aside className="builder__list" aria-label="Change requests">
          {listError && <p className="form__error">{listError}</p>}
          {changes === null && !listError && <p className="muted">Loading…</p>}
          {changes?.length === 0 && <p className="muted">No change requests yet.</p>}
          {changes && changes.some((c) => c.status !== 'DISCARDED') && (
            <ChangeList label="Open change requests" changes={changes.filter((c) => c.status !== 'DISCARDED')} openId={id} />
          )}
          {changes && changes.some((c) => c.status === 'DISCARDED') && (
            <>
              <h2 className="builder__list-heading">Closed</h2>
              <ChangeList label="Closed change requests" changes={changes.filter((c) => c.status === 'DISCARDED')} openId={id} />
            </>
          )}
        </aside>

        <section className="builder__main">
          {id ? (
            <Conversation key={id} id={id} status={status} onChanged={refreshList} />
          ) : (
            <NewChange
              onStarted={(detail) => {
                refreshList();
                navigate(`/builder/${detail.change.id}`);
              }}
            />
          )}
        </section>
      </div>
    </div>
  );
}

function ChangeList({ label, changes, openId }: { label: string; changes: ChangeSummary[]; openId?: string }) {
  return (
    <ul aria-label={label}>
      {changes.map((change) => (
        <li key={change.id}>
          <Link
            to={`/builder/${change.id}`}
            className={`builder__item${change.id === openId ? ' builder__item--open' : ''}${change.status === 'DISCARDED' ? ' builder__item--closed' : ''}`}
            aria-current={change.id === openId ? 'page' : undefined}
          >
            <span className="builder__item-title">{change.title}</span>
            <span className="muted builder__item-sub">
              {change.number} · {change.working ? 'Agent working' : STATUS_LABEL[change.status]} · {change.requestedBy}
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

function NewChange({ onStarted }: { onStarted: (detail: ChangeDetail) => void }) {
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async (text: string) => {
    setSending(true);
    setError(null);
    try {
      onStarted(await startChange(text));
    } catch (e) {
      setError(errorMessage(e));
      setSending(false);
    }
  };

  return (
    <div className="builder__new">
      <h2>What would you like to change?</h2>
      <p className="muted">
        Say it the way you'd tell a colleague, for example: "When receiving chilled items, ask for the temperature on arrival and
        don't let the LPN close without it."
      </p>
      <Composer label="Describe the change" busy={sending} onSend={send} placeholder="Describe the change" />
      {error && <p className="form__error">{error}</p>}
    </div>
  );
}

function Conversation({ id, status, onChanged }: { id: string; status: BuilderStatus | null; onChanged: () => void }) {
  const [detail, setDetail] = useState<ChangeDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sendError, setSendError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const wasWorking = useRef(false);
  const [developerMode, setDeveloperMode] = useDeveloperMode();
  /** The questions the person closed the form for; it opens by itself only for new ones. */
  const [setAside, setSetAside] = useState<string | null>(null);
  const end = useRef<HTMLDivElement>(null);

  const load = useCallback(
    (signal?: AbortSignal) =>
      getChange(id, signal)
        .then((found) => {
          setDetail(found);
          setError(null);
          if (wasWorking.current && !found.change.working) onChanged();
          wasWorking.current = found.change.working;
        })
        .catch((e) => {
          if (!signal?.aborted) setError(errorMessage(e));
        }),
    [id, onChanged],
  );

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const working = detail?.change.working ?? false;
  const previewStarting = detail?.preview?.state === 'STARTING';
  useEffect(() => {
    if (!working && !previewStarting) return;
    const handle = setInterval(() => load(), POLL_MS);
    return () => clearInterval(handle);
  }, [working, previewStarting, load]);

  // Looking at it is what it needed from you: the bell stops counting it.
  const loaded = detail !== null;
  useEffect(() => {
    if (!loaded || working) return;
    markSeen(id)
      .then(() => window.dispatchEvent(new Event(BUILDER_SEEN)))
      .catch(() => undefined);
  }, [id, loaded, working]);

  const entryCount = detail?.entries.length ?? 0;
  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'end' });
  }, [entryCount, working]);

  const say = async (text: string) => {
    setSending(true);
    setSendError(null);
    try {
      const updated = await sayToAgent(id, text);
      setDetail(updated);
      wasWorking.current = updated.change.working;
      onChanged();
      return true;
    } catch (e) {
      setSendError(errorMessage(e));
      return false;
    } finally {
      setSending(false);
    }
  };

  const stop = async () => {
    try {
      setDetail(await stopAgent(id));
    } catch (e) {
      setSendError(errorMessage(e));
    }
  };

  const act = async (action: () => Promise<ChangeDetail>, after?: () => void) => {
    setSendError(null);
    try {
      setDetail(await action());
      after?.();
    } catch (e) {
      setSendError(errorMessage(e));
    }
  };

  if (error && !detail) return <p className="form__error">{error}</p>;
  if (!detail) return <p className="muted">Loading…</p>;

  // Only developers who have turned on developer mode see how the change is built (AB-30, AB-37).
  const technical = detail.developer && developerMode;
  const results = new Map<string, Extract<Block, { type: 'tool_result' }>>();
  detail.entries.forEach((entry) =>
    entry.blocks.forEach((block) => {
      if (block.type === 'tool_result') results.set(block.callId, block);
    }),
  );

  return (
    <div className="builder__conversation">
      <header className="builder__head">
        <div>
          <h2>{detail.change.title}</h2>
          <p className="muted">
            {detail.change.number} · asked by {detail.change.requestedBy}
          </p>
        </div>
        {detail.discarded ? (
          <button type="button" className="button" onClick={() => act(() => reopenChange(id), onChanged)}>
            <RotateCcw size={16} aria-hidden="true" />
            Reopen
          </button>
        ) : (
          <ChangeActions
            detail={detail}
            status={status}
            onPreview={() => act(() => startPreview(id))}
            onAccept={() => act(() => acceptChange(id), onChanged)}
            onDiscard={(reason) => act(() => discardChange(id, reason), onChanged)}
          />
        )}
      </header>

      {detail.discarded && (
        <p className="notice-info builder__closed" role="status">
          Closed by {detail.discarded.by} on {new Date(detail.discarded.at).toLocaleString()}
          {detail.discarded.reason ? `: ${detail.discarded.reason}` : '.'}
          {detail.accepted && ` It is still in ${detail.accepted.into}; closing it doesn't take it out.`}
        </p>
      )}

      {detail.questions && !working && (
        <>
          <div className="builder__waiting" role="status">
            <MessageCircleQuestion size={18} aria-hidden="true" />
            <span>
              The agent is waiting for your {detail.questions.questions.length === 1 ? 'answer' : 'answers'} before it builds
              anything.
            </span>
            <button type="button" className="button button--primary" onClick={() => setSetAside(null)}>
              Answer
            </button>
          </div>
          {setAside !== detail.questions.callId && (
            <QuestionsModal
              key={detail.questions.callId}
              pending={detail.questions}
              onClose={() => setSetAside(detail.questions!.callId)}
              onSend={async (answers) => {
                const updated = await answerQuestions(id, detail.questions!.callId, answers);
                setDetail(updated);
                wasWorking.current = updated.change.working;
                onChanged();
              }}
            />
          )}
        </>
      )}
      {detail.accepted && <AcceptedNote detail={detail} />}
      {detail.preview && (
        <PreviewPanel detail={detail} onRestart={() => act(() => startPreview(id))} onStop={() => act(() => stopPreview(id))} />
      )}

      <ol className="builder__entries" aria-label="Conversation">
        {turns(detail.entries, technical).map((turn) =>
          'work' in turn ? (
            <li key={turn.key} className="turn turn--agent">
              <WorkLine calls={turn.work} results={results} />
            </li>
          ) : (
            <li key={turn.entry.seq} className={turn.entry.role === 'PERSON' ? 'turn turn--person' : 'turn turn--agent'}>
              <EntryView entry={turn.entry} results={results} change={detail.change} technical={technical} />
            </li>
          ),
        )}
      </ol>

      {working && (
        <div className="builder__working" role="status">
          <span className="builder__dot" aria-hidden="true" />
          {currentPurpose(detail) ? `Working on it: ${currentPurpose(detail)}…` : 'The agent is working on this…'}
          <button type="button" className="button" onClick={stop}>
            <Square size={14} aria-hidden="true" />
            Stop
          </button>
        </div>
      )}
      {!working && detail.lastProblem && (
        <div className="notice" role="alert">
          <p>{detail.lastProblem}</p>
        </div>
      )}
      <div ref={end} />

      {!detail.discarded && (
        <Composer
          label="Reply to the agent"
          placeholder={
            working
              ? 'You can reply once the agent has finished'
              : detail.questions
                ? 'Or answer the questions in your own words'
                : 'Reply, or ask for more changes'
          }
          busy={sending}
          disabled={working}
          onSend={say}
        />
      )}
      {sendError && <p className="form__error">{sendError}</p>}

      {detail.developer && (
        <label className="dev-mode">
          <input type="checkbox" checked={developerMode} onChange={(e) => setDeveloperMode(e.target.checked)} />
          Developer mode
          <span className="muted"> shows the code, files and test results behind each step</span>
        </label>
      )}
      {technical && (
        <details className="builder__dev">
          <summary>Developer details</summary>
          <DeveloperDetails detail={detail} />
        </details>
      )}
    </div>
  );
}

const CHANGE_KIND: Record<string, { label: string; className: string }> = {
  M: { label: 'Changed', className: 'file-kind--changed' },
  A: { label: 'Added', className: 'file-kind--added' },
  D: { label: 'Deleted', className: 'file-kind--deleted' },
};

/** One line of the files list, "M path", split into what happened, the file's name and its folder. */
export function fileChange(line: string) {
  const [code, ...rest] = line.split(' ');
  const path = rest.join(' ');
  const cut = path.lastIndexOf('/');
  return {
    kind: CHANGE_KIND[code.charAt(0)] ?? {
      label: code,
      className: 'file-kind--changed',
    },
    name: path.slice(cut + 1),
    folder: cut >= 0 ? path.slice(0, cut) : '',
  };
}

/** Where the change is in git, what it cost in tokens, and which files it touches. For developers; hidden by default. */
function DeveloperDetails({ detail }: { detail: ChangeDetail }) {
  const files = detail.files.map(fileChange);
  const counts = Object.values(CHANGE_KIND)
    .map((kind) => [kind.label.toLowerCase(), files.filter((f) => f.kind.label === kind.label).length] as const)
    .filter(([, n]) => n > 0)
    .map(([label, n]) => `${n} ${label}`);
  return (
    <div className="dev">
      <dl className="dev__facts">
        <div>
          <dt>Branch</dt>
          <dd>
            <code>{detail.branch}</code>
          </dd>
        </div>
        <div>
          <dt>Model use</dt>
          <dd>
            {detail.inputTokens.toLocaleString()} tokens in · {detail.outputTokens.toLocaleString()} out
          </dd>
        </div>
      </dl>
      <h3 className="dev__heading">
        Files <span className="muted">{files.length === 0 ? 'none changed yet' : `${files.length}: ${counts.join(', ')}`}</span>
      </h3>
      {files.length > 0 && (
        <ul className="dev__files" aria-label="Files changed">
          {files.map((file) => (
            <li key={`${file.folder}/${file.name}`}>
              <span className={`file-kind ${file.kind.className}`}>{file.kind.label}</span>
              <span className="dev__file">
                <span className="dev__name">{file.name}</span>
                {file.folder && <span className="dev__folder">{file.folder}</span>}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const STATUS_LABEL: Record<ChangeSummary['status'], string> = {
  DRAFTING: 'Drafting',
  ACCEPTED: 'Accepted',
  IN_REVIEW: 'In review',
  RELEASED: 'Released',
  DISCARDED: 'Discarded',
};

/** Trying the change, and putting it into the environment changes are tried out in together. */
function ChangeActions({
  detail,
  status,
  onPreview,
  onAccept,
  onDiscard,
}: {
  detail: ChangeDetail;
  status: BuilderStatus | null;
  onPreview: () => void;
  onAccept: () => Promise<void>;
  onDiscard: (reason: string | null) => Promise<void>;
}) {
  const [confirming, setConfirming] = useState(false);
  const [accepting, setAccepting] = useState(false);
  const [discarding, setDiscarding] = useState(false);
  const [reason, setReason] = useState('');
  const [closing, setClosing] = useState(false);
  const busy = detail.change.working;
  const nothing = detail.files.length === 0;
  const acceptedAsIs = detail.accepted?.upToDate ?? false;
  const into = status?.acceptBranch ?? 'builder';

  if (discarding) {
    return (
      <div className="builder__confirm" role="group" aria-label="Confirm close">
        <p>
          Closing stops the agent and the preview. You can reopen it later.
          {detail.accepted && ` It stays in ${detail.accepted.into}: closing doesn't take it out.`}
        </p>
        <textarea
          aria-label="Why it is being closed"
          placeholder="Why, if you'd like to say (optional)"
          rows={2}
          maxLength={500}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
        <div className="button-row">
          <button type="button" className="button" onClick={() => setDiscarding(false)} disabled={closing}>
            Cancel
          </button>
          <button
            type="button"
            className="button button--danger"
            disabled={closing}
            onClick={async () => {
              setClosing(true);
              await onDiscard(reason.trim() || null);
              setClosing(false);
              setDiscarding(false);
            }}
          >
            {closing ? 'Closing…' : 'Close change request'}
          </button>
        </div>
      </div>
    );
  }

  if (confirming) {
    return (
      <div className="builder__confirm" role="group" aria-label="Confirm accept">
        <p>
          Accepting puts this change into <strong>{into}</strong>, which everyone trying changes uses. It isn't released to the
          base WMS.
        </p>
        <div className="button-row">
          <button type="button" className="button" onClick={() => setConfirming(false)} disabled={accepting}>
            Cancel
          </button>
          <button
            type="button"
            className="button button--primary"
            disabled={accepting}
            onClick={async () => {
              setAccepting(true);
              await onAccept();
              setAccepting(false);
              setConfirming(false);
            }}
          >
            {accepting ? 'Accepting…' : 'Accept change'}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="button-row">
      <button type="button" className="button button--danger-quiet" onClick={() => setDiscarding(true)}>
        <Archive size={16} aria-hidden="true" />
        Discard
      </button>
      {status?.previews && !detail.preview && (
        <button type="button" className="button" onClick={onPreview} disabled={busy || nothing}>
          <Play size={16} aria-hidden="true" />
          Preview
        </button>
      )}
      <button
        type="button"
        className="button button--primary"
        onClick={() => setConfirming(true)}
        disabled={busy || nothing || acceptedAsIs}
        title={nothing ? 'The agent hasn’t changed anything yet' : acceptedAsIs ? 'Already accepted as it is' : undefined}
      >
        <CheckCircle2 size={16} aria-hidden="true" />
        {detail.accepted && !acceptedAsIs ? 'Accept again' : 'Accept'}
      </button>
    </div>
  );
}

function AcceptedNote({ detail }: { detail: ChangeDetail }) {
  const accepted = detail.accepted!;
  return (
    <p className={accepted.upToDate ? 'notice-info' : 'notice-info builder__stale'} role="status">
      {accepted.upToDate
        ? `Accepted into ${accepted.into} by ${accepted.by} on ${new Date(accepted.at).toLocaleString()}.`
        : `Changed since ${accepted.by} accepted it on ${new Date(accepted.at).toLocaleString()}. Accept it again to put the changes into ${accepted.into}.`}
    </p>
  );
}

function PreviewPanel({ detail, onRestart, onStop }: { detail: ChangeDetail; onRestart: () => void; onStop: () => void }) {
  const preview = detail.preview!;
  const busy = detail.change.working;
  return (
    <section className={`preview preview--${preview.state.toLowerCase()}`} aria-label="Preview">
      {preview.state === 'STARTING' && (
        <p role="status">
          <span className="builder__dot" aria-hidden="true" /> Starting the preview: {preview.step ?? 'getting ready'}… This takes
          a minute or two.
        </p>
      )}
      {preview.state === 'RUNNING' && (
        <p>
          The preview is running with its own demo warehouse:{' '}
          <a href={preview.url ?? undefined} target="_blank" rel="noreferrer">
            {preview.url} <ExternalLink size={14} aria-hidden="true" />
          </a>
          {!preview.upToDate && (
            <span className="builder__stale"> It shows the change as it was; restart it to see the latest.</span>
          )}
        </p>
      )}
      {preview.state === 'RUNNING' && preview.signIn && <PreviewSignIn signIn={preview.signIn} />}
      {preview.state === 'FAILED' && (
        <div role="alert">
          <p>The preview couldn't start.</p>
          {preview.problem && (detail.developer ? <pre className="step__output">{preview.problem}</pre> : <p>{preview.problem}</p>)}
        </div>
      )}
      <div className="button-row">
        {preview.state !== 'STARTING' && (
          <button type="button" className="button" onClick={onRestart} disabled={busy}>
            <Play size={14} aria-hidden="true" />
            {preview.state === 'FAILED' ? 'Try again' : 'Restart'}
          </button>
        )}
        <button type="button" className="button" onClick={onStop}>
          <Square size={14} aria-hidden="true" />
          Stop preview
        </button>
      </div>
    </section>
  );
}

/** The sign-in made for the preview, so nobody has to set up an administrator in it. */
function PreviewSignIn({ signIn }: { signIn: NonNullable<NonNullable<ChangeDetail['preview']>['signIn']> }) {
  const [shown, setShown] = useState(false);
  const [copied, setCopied] = useState(false);
  if (!signIn.userCode || !signIn.password) {
    return signIn.note ? <p className="muted">{signIn.note}</p> : null;
  }
  const password = signIn.password;
  return (
    <p className="preview__signin">
      Sign in as <strong>{signIn.userCode}</strong> with password{' '}
      <code aria-label="Preview password">{shown ? password : '\u2022'.repeat(password.length)}</code>
      <button type="button" className="link-button link-button--small" onClick={() => setShown((s) => !s)}>
        {shown ? 'Hide' : 'Show'}
      </button>
      <button
        type="button"
        className="link-button link-button--small"
        onClick={async () => {
          try {
            await navigator.clipboard.writeText(password);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
          } catch {
            setShown(true);
          }
        }}
      >
        {copied ? 'Copied' : 'Copy'}
      </button>
      <span className="muted">This password only works in this preview.</span>
    </p>
  );
}

function EntryView({
  entry,
  results,
  change,
  technical,
}: {
  entry: Entry;
  results: Map<string, Extract<Block, { type: 'tool_result' }>>;
  change: ChangeSummary;
  technical: boolean;
}) {
  const texts = entry.blocks.filter((b): b is Extract<Block, { type: 'text' }> => b.type === 'text' && b.text.trim() !== '');
  const allCalls = entry.blocks.filter((b): b is Extract<Block, { type: 'tool_call' }> => b.type === 'tool_call');
  const asked = allCalls.find((call) => call.name === 'ask_questions');
  const reported = allCalls.find((call) => call.name === 'report_change');
  const calls = allCalls.filter((call) => call !== asked && call !== reported);
  // Answers to the agent's questions are kept as a tool result; they are what the person said.
  const answers =
    entry.role === 'PERSON' && texts.length === 0
      ? entry.blocks.filter((b): b is Extract<Block, { type: 'tool_result' }> => b.type === 'tool_result')
      : [];
  return (
    <>
      {texts.map((text, i) =>
        entry.role === 'AGENT' ? (
          <RichText key={i} text={text.text} />
        ) : (
          <p key={i} className="turn__text">
            {text.text}
          </p>
        ),
      )}
      {answers.map((answer) => (
        <p key={answer.callId} className="turn__text">
          {answer.content}
        </p>
      ))}
      {asked && <AskedQuestions call={asked} answered={results.has(asked.id)} />}
      {reported && <SummaryCard report={readReport(reported.input)} title={change.title} number={change.number} />}
      {calls.length > 0 && !technical && <WorkLine calls={calls} results={results} />}
      {calls.length > 0 && technical && (
        <details className="steps">
          <summary>
            {calls.length === 1 ? stepLabel(calls[0]) : `${calls.length} steps: ${[...new Set(calls.map(stepKind))].join(', ')}`}
          </summary>
          <ul>
            {calls.map((call) => {
              const result = results.get(call.id);
              return (
                <li key={call.id} className={result?.error ? 'step step--refused' : 'step'}>
                  <span>{stepLabel(call)}</span>
                  {!result && <span className="muted"> (not done)</span>}
                  {result && (
                    <details>
                      <summary className="muted">{result.error ? 'Not done' : 'Result'}</summary>
                      <pre className="step__output">{result.content}</pre>
                    </details>
                  )}
                </li>
              );
            })}
          </ul>
        </details>
      )}
    </>
  );
}

/** What a step says it is for, in the agent's plain words. */
function purposeOf(call: Extract<Block, { type: 'tool_call' }>): string | null {
  const purpose = call.input.purpose;
  return typeof purpose === 'string' && purpose.trim() !== '' ? purpose.trim() : null;
}

/** What the agent is doing now: the purpose of its latest step. */
function currentPurpose(detail: ChangeDetail): string | null {
  for (let i = detail.entries.length - 1; i >= 0; i--) {
    const entry = detail.entries[i];
    if (entry.role !== 'AGENT') continue;
    const calls = entry.blocks.filter((b): b is Extract<Block, { type: 'tool_call' }> => b.type === 'tool_call');
    const purpose = calls.map(purposeOf).filter((p): p is string => p !== null).at(-1);
    if (purpose) return purpose;
  }
  return null;
}

/** What the agent did in a turn, in business terms: what kind of work, never on which code. */
/** How many lines of work are shown before "Show more". */
const WORK_LINES = 6;

type ToolCall = Extract<Block, { type: 'tool_call' }>;

/**
 * The conversation as shown. Outside developer mode, a run of the agent's turns in which it only worked, saying nothing,
 * is one bubble of what it did, not a bubble per step.
 */
function turns(entries: Entry[], technical: boolean): ({ entry: Entry } | { key: string; work: ToolCall[] })[] {
  const out: ({ entry: Entry } | { key: string; work: ToolCall[] })[] = [];
  for (const entry of entries) {
    if (entry.role === 'RESULTS') continue;
    const onlyWork =
      !technical &&
      entry.role === 'AGENT' &&
      entry.blocks.every(
        (b) => (b.type === 'text' && b.text.trim() === '') || (b.type === 'tool_call' && b.name !== 'ask_questions' && b.name !== 'report_change'),
      );
    const calls = entry.blocks.filter((b): b is ToolCall => b.type === 'tool_call');
    const last = out.at(-1);
    if (onlyWork && calls.length > 0) {
      if (last && 'work' in last) last.work.push(...calls);
      else out.push({ key: `work-${entry.seq}`, work: [...calls] });
    } else {
      out.push({ entry });
    }
  }
  return out;
}

const LOOKING = ['list_files', 'read_file', 'search_code'];
const CHANGING = ['write_file', 'edit_file'];

function listed(things: string[]): string {
  return things.length <= 1 ? (things[0] ?? '') : `${things.slice(0, -1).join(', ')} and ${things[things.length - 1]}`;
}

/**
 * What the agent did, in business terms: each step as the agent described it, or else what kind of work it was and in
 * which part of the WMS, never on which code.
 */
export function WorkLine({ calls, results }: { calls: ToolCall[]; results: Map<string, Extract<Block, { type: 'tool_result' }>> }) {
  const [all, setAll] = useState(false);
  const lines: string[] = [];
  const looked: string[] = [];
  const changed: string[] = [];
  let lookedAt = -1;
  let changedAt = -1;
  for (const call of calls) {
    const purpose = purposeOf(call);
    const area = typeof call.input.area === 'string' ? call.input.area : null;
    if (purpose) {
      if (!lines.includes(purpose)) lines.push(purpose);
    } else if (LOOKING.includes(call.name)) {
      if (lookedAt < 0) lookedAt = lines.push('') - 1;
      if (area && !looked.includes(area)) looked.push(area);
    } else if (CHANGING.includes(call.name)) {
      if (changedAt < 0) changedAt = lines.push('') - 1;
      if (area && !changed.includes(area)) changed.push(area);
    }
  }
  // Steps from before the agent said what each was for: what kind of work, and where.
  if (lookedAt >= 0) lines[lookedAt] = looked.length > 0 ? `Looked at ${listed(looked)}` : 'Looked through the WMS';
  if (changedAt >= 0) lines[changedAt] = changed.length > 0 ? `Changed ${listed(changed)}` : 'Made changes';

  const checks = calls.filter((c) => c.name === 'run_checks');
  const checked =
    checks.length === 0
      ? null
      : checks.every((c) => {
          const result = results.get(c.id);
          return result && !result.error && (result.content === 'passed' || result.content.startsWith('Passed'));
        });
  if (lines.length === 0 && checked === null) lines.push('Worked on the change');
  const shown = all ? lines : lines.slice(0, WORK_LINES);

  return (
    <ul className="work-line work-line--steps">
      {shown.map((line) => (
        <li key={line}>
          <Hammer size={14} aria-hidden="true" />
          <span>{line}</span>
        </li>
      ))}
      {lines.length > shown.length && (
        <li>
          <button type="button" className="link-button link-button--small" onClick={() => setAll(true)}>
            Show {lines.length - shown.length} more
          </button>
        </li>
      )}
      {checked !== null && (
        <li className={checked ? 'work-line__passed' : 'work-line__failed'}>
          {checked ? 'The checks passed.' : 'The checks found something to fix.'}
        </li>
      )}
    </ul>
  );
}

/** The questions the agent asked, as they were put to the person. */
function AskedQuestions({ call, answered }: { call: Extract<Block, { type: 'tool_call' }>; answered: boolean }) {
  const questions = Array.isArray(call.input.questions) ? (call.input.questions as { question?: unknown }[]) : [];
  return (
    <div className="asked">
      <p className="asked__head">
        <MessageCircleQuestion size={16} aria-hidden="true" />
        {answered ? 'Asked you' : 'Questions for you'}
      </p>
      <ol>
        {questions.map((q, i) => (
          <li key={i}>{String(q.question ?? '')}</li>
        ))}
      </ol>
    </div>
  );
}

const KINDS: Record<string, string> = {
  list_files: 'looked around',
  read_file: 'read',
  search_code: 'searched',
  write_file: 'wrote',
  edit_file: 'changed',
  run_checks: 'ran checks',
};

function stepKind(call: Extract<Block, { type: 'tool_call' }>): string {
  return KINDS[call.name] ?? call.name;
}

/** A step in plain words, with what it was about for those who want to look closer. */
export function stepLabel(call: Extract<Block, { type: 'tool_call' }>): string {
  const input = call.input;
  const path = typeof input.path === 'string' ? input.path : '';
  switch (call.name) {
    case 'list_files':
      return path ? `Looked at what is in ${path}` : 'Looked at what is in the WMS';
    case 'read_file':
      return `Read ${path}`;
    case 'search_code':
      return `Searched for "${String(input.pattern ?? '')}"${path ? ` in ${path}` : ''}`;
    case 'write_file':
      return `Wrote ${path}`;
    case 'edit_file':
      return `Changed ${path}`;
    case 'run_checks':
      return `Ran the ${input.which === 'frontend' ? 'screen' : 'server'} checks${input.only ? ` (${String(input.only)})` : ''}`;
    default:
      return call.name;
  }
}

function Composer({
  label,
  placeholder,
  busy,
  disabled = false,
  onSend,
}: {
  label: string;
  placeholder: string;
  busy: boolean;
  disabled?: boolean;
  onSend: (text: string) => Promise<boolean | void>;
}) {
  const [text, setText] = useState('');

  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    if (!text.trim() || busy || disabled) return;
    const sent = await onSend(text.trim());
    if (sent !== false) setText('');
  };

  const keys = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      void submit();
    }
  };

  return (
    <form className="composer" onSubmit={submit}>
      <textarea
        aria-label={label}
        placeholder={placeholder}
        value={text}
        rows={4}
        disabled={disabled}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={keys}
      />
      <div className="composer__bar">
        <span className="muted">Ctrl+Enter to send</span>
        <button type="submit" className="button button--primary" disabled={busy || disabled || !text.trim()}>
          <Send size={16} aria-hidden="true" />
          {busy ? 'Sending…' : 'Send'}
        </button>
      </div>
    </form>
  );
}
