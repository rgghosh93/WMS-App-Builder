import type { ReactNode } from 'react';

/**
 * The agent's message with its light formatting shown: paragraphs, bulleted and numbered lists, **bold** and `code`.
 * Nothing else is interpreted, and no HTML from the message is ever used.
 */
export function RichText({ text }: { text: string }) {
  const blocks: ReactNode[] = [];
  let list: { ordered: boolean; items: string[] } | null = null;
  let paragraph: string[] = [];

  const flushParagraph = () => {
    if (paragraph.length > 0) {
      const lines = paragraph;
      blocks.push(
        <p key={blocks.length} className="rich__p">
          {lines.map((line, i) => (
            <span key={i}>
              {i > 0 && <br />}
              {inline(line)}
            </span>
          ))}
        </p>,
      );
      paragraph = [];
    }
  };
  const flushList = () => {
    if (list) {
      const items = list.items.map((item, i) => <li key={i}>{inline(item)}</li>);
      blocks.push(
        list.ordered ? (
          <ol key={blocks.length} className="rich__list">
            {items}
          </ol>
        ) : (
          <ul key={blocks.length} className="rich__list">
            {items}
          </ul>
        ),
      );
      list = null;
    }
  };

  for (const raw of text.split('\n')) {
    const line = raw.trimEnd();
    const bullet = /^\s*[-*•]\s+(.*)$/.exec(line);
    const numbered = /^\s*\d+[.)]\s+(.*)$/.exec(line);
    const heading = /^\s*#{1,6}\s+(.*)$/.exec(line);
    if (bullet || numbered) {
      flushParagraph();
      const ordered = Boolean(numbered);
      if (list && list.ordered !== ordered) flushList();
      if (!list) list = { ordered, items: [] };
      list.items.push((bullet ?? numbered)![1]);
    } else if (line.trim() === '') {
      flushParagraph();
      flushList();
    } else if (heading || /^\*\*[^*]+\*\*:?$/.test(line.trim())) {
      flushParagraph();
      flushList();
      const title = heading ? heading[1] : line.trim().replace(/^\*\*|\*\*:?$/g, '');
      blocks.push(
        <p key={blocks.length} className="rich__heading">
          {inline(title)}
        </p>,
      );
    } else {
      flushList();
      paragraph.push(line);
    }
  }
  flushParagraph();
  flushList();
  return <div className="rich">{blocks}</div>;
}

/** **bold** and `code` within a line. */
function inline(text: string): ReactNode[] {
  const parts: ReactNode[] = [];
  const pattern = /\*\*([^*]+)\*\*|`([^`]+)`/g;
  let at = 0;
  for (let match = pattern.exec(text); match; match = pattern.exec(text)) {
    if (match.index > at) parts.push(text.slice(at, match.index));
    parts.push(match[1] !== undefined ? <strong key={match.index}>{match[1]}</strong> : <code key={match.index}>{match[2]}</code>);
    at = match.index + match[0].length;
  }
  if (at < text.length) parts.push(text.slice(at));
  return parts;
}
