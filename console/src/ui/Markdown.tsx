import MarkdownIt from 'markdown-it';
import { useMemo } from 'react';
import type { MouseEvent } from 'react';
import { useNavigate } from 'react-router';
import { cx } from './controls';
import styles from './markdown.module.css';

// Model output and stored memories are untrusted. With html:false markdown-it escapes raw HTML,
// and its default validateLink rejects javascript:/vbscript:/data: links.
const parser = new MarkdownIt({ html: false, linkify: true, breaks: true });

const defaultLinkOpen = parser.renderer.rules.link_open
  ?? ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options));
parser.renderer.rules.link_open = (tokens, index, options, env, self) => {
  const href = tokens[index].attrGet('href') ?? '';
  if (/^(?:https?:|mailto:)/i.test(href)) {
    tokens[index].attrSet('target', '_blank');
    tokens[index].attrSet('rel', 'noopener noreferrer');
  }
  return defaultLinkOpen(tokens, index, options, env, self);
};

const CITATION = /\[((?:E|S)?\d{1,3})\]/g;

export interface MarkdownProps {
  source: string | null | undefined;
  inline?: boolean;
  className?: string;
  variant?: 'body' | 'answer' | 'article';
  /** Turns `[1]`/`[E1]` markers into links; return null to leave the text alone. */
  citationHref?: (ref: string) => string | null;
}

function escapeAttr(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

export function renderMarkdown(source: string, inline = false, citationHref?: (ref: string) => string | null): string {
  const html = inline ? parser.renderInline(source) : parser.render(source);
  if (!citationHref) return html;
  // Only rewrite text outside tags and code spans; citations never appear inside attributes.
  return html.replace(/(<code[\s\S]*?<\/code>)|(<[^>]+>)|([^<]+)/g, (match, code, tag, text) => {
    if (code || tag || !text) return match;
    return text.replace(CITATION, (whole: string, ref: string) => {
      const href = citationHref(ref);
      return href ? `<a class="citation" href="${escapeAttr(href)}">[${ref}]</a>` : whole;
    });
  });
}

export function Markdown({ source, inline, className, variant = 'body', citationHref }: MarkdownProps) {
  const navigate = useNavigate();
  const html = useMemo(() => renderMarkdown(String(source ?? ''), inline, citationHref), [source, inline, citationHref]);
  const onClick = (event: MouseEvent<HTMLElement>) => {
    const anchor = (event.target as HTMLElement).closest('a');
    const href = anchor?.getAttribute('href');
    if (!href || event.metaKey || event.ctrlKey || event.shiftKey) return;
    if (href.startsWith('#')) {
      event.preventDefault();
      document.getElementById(href.slice(1))?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    if (href.startsWith('/console/')) {
      event.preventDefault();
      navigate(href.slice('/console'.length));
    }
  };
  const Tag = inline ? 'span' : 'div';
  return <Tag className={cx(styles.markdown, styles[variant], className)} onClick={onClick} dangerouslySetInnerHTML={{ __html: html }} />;
}
