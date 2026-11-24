import { useEffect, useId, useRef, useState } from 'react';
import { X } from 'lucide-react';
import { ApiError, errorMessage } from '../../api/http';
import type { Answer, PendingQuestions, Question } from '../../api/builder';

interface Choice {
  chosen: string[];
  other: string;
  otherOn: boolean;
}

/** What is picked to start with: the agent's recommendation, if it made one. */
function initial(question: Question): Choice {
  return { chosen: question.recommended ? [question.recommended] : [], other: '', otherOn: question.options.length === 0 };
}

function answered(choice: Choice): boolean {
  return choice.chosen.length > 0 || (choice.otherOn && choice.other.trim() !== '');
}

/**
 * The agent's questions as a form: a choice for each, or the person's own words. The agent waits until it is sent.
 * Closing it leaves the questions waiting; they can be answered in the chat instead.
 */
export function QuestionsModal({
  pending,
  onSend,
  onClose,
}: {
  pending: PendingQuestions;
  onSend: (answers: Answer[]) => Promise<void>;
  onClose: () => void;
}) {
  const titleId = useId();
  const [choices, setChoices] = useState<Choice[]>(() => pending.questions.map(initial));
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const panel = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    panel.current?.querySelector<HTMLElement>('input, textarea')?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCloseRef.current();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      before?.focus?.();
    };
  }, []);

  const update = (index: number, change: (choice: Choice) => Choice) =>
    setChoices((all) => all.map((choice, i) => (i === index ? change(choice) : choice)));

  const pick = (index: number, question: Question, label: string, on: boolean) =>
    update(index, (choice) => {
      if (!question.multiple) return { ...choice, chosen: on ? [label] : [], otherOn: question.options.length === 0 ? true : false };
      return { ...choice, chosen: on ? [...choice.chosen, label] : choice.chosen.filter((c) => c !== label) };
    });

  const ready = choices.every(answered);

  const send = async () => {
    setSending(true);
    setError(null);
    setFieldErrors({});
    try {
      await onSend(
        choices.map((choice, question) => ({
          question,
          chosen: choice.chosen,
          other: choice.otherOn && choice.other.trim() ? choice.other.trim() : null,
        })),
      );
    } catch (e) {
      setError(errorMessage(e));
      if (e instanceof ApiError) setFieldErrors(e.fieldErrors);
      setSending(false);
    }
  };

  return (
    <div className="modal-layer">
      <div className="drawer-backdrop" onClick={onClose} aria-hidden="true" />
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={panel}>
        <header className="modal__header">
          <div>
            <h2 id={titleId}>{pending.questions.length === 1 ? 'The agent has a question' : 'The agent has a few questions'}</h2>
            {pending.intro && <p className="muted">{pending.intro}</p>}
          </div>
          <button type="button" className="icon-button" aria-label="Close" onClick={onClose}>
            <X size={18} aria-hidden="true" />
          </button>
        </header>

        <form
          className="modal__body"
          onSubmit={(event) => {
            event.preventDefault();
            if (ready && !sending) void send();
          }}
        >
          {pending.questions.map((question, index) => {
            const choice = choices[index];
            const name = `${titleId}-q${index}`;
            const problem = fieldErrors[`q${index}`];
            return (
              <fieldset key={index} className={`question${problem ? ' question--invalid' : ''}`}>
                <legend>
                  <span className="question__number">{index + 1}</span>
                  {question.question}
                </legend>
                {question.multiple && <p className="muted question__hint">Pick any that apply.</p>}
                <div className="question__options">
                  {question.options.map((option) => (
                    <label key={option.label} className={`option${choice.chosen.includes(option.label) ? ' option--on' : ''}`}>
                      <input
                        type={question.multiple ? 'checkbox' : 'radio'}
                        name={name}
                        checked={choice.chosen.includes(option.label)}
                        onChange={(e) => pick(index, question, option.label, e.target.checked)}
                      />
                      <span className="option__text">
                        <span className="option__label">
                          {option.label}
                          {option.label === question.recommended && <span className="badge badge--sm option__tag">Recommended</span>}
                        </span>
                        {option.description && <span className="muted option__description">{option.description}</span>}
                      </span>
                    </label>
                  ))}
                  {question.options.length > 0 && (
                    <label className={`option${choice.otherOn ? ' option--on' : ''}`}>
                      <input
                        type={question.multiple ? 'checkbox' : 'radio'}
                        name={name}
                        checked={choice.otherOn}
                        onChange={(e) =>
                          update(index, (c) => ({ ...c, otherOn: e.target.checked, chosen: question.multiple ? c.chosen : [] }))
                        }
                      />
                      <span className="option__text">
                        <span className="option__label">Something else</span>
                      </span>
                    </label>
                  )}
                </div>
                {choice.otherOn && (
                  <textarea
                    aria-label={question.options.length > 0 ? `Your answer to question ${index + 1}` : question.question}
                    placeholder="Your answer"
                    rows={2}
                    value={choice.other}
                    onChange={(e) => update(index, (c) => ({ ...c, other: e.target.value }))}
                  />
                )}
                {problem && <p className="field__error">{problem}</p>}
              </fieldset>
            );
          })}
          {error && <p className="form__error">{error}</p>}

          <footer className="modal__footer">
            <button type="button" className="link-button" onClick={onClose}>
              Answer in the chat instead
            </button>
            <button type="submit" className="button button--primary" disabled={!ready || sending}>
              {sending ? 'Sending…' : 'Send answers'}
            </button>
          </footer>
        </form>
      </div>
    </div>
  );
}
