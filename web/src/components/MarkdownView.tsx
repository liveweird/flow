import { Typography } from "@mantine/core";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkGemoji from "remark-gemoji";

// Read-only renderer for the markdown strings the app carries (today: changelog bodies).
// Single home for the Mantine Typography wrapper + the GFM plugin set (Lettuce's).
export default function MarkdownView({ children }: { children: string | null | undefined }) {
  return (
    <Typography>
      <ReactMarkdown remarkPlugins={[remarkGfm, remarkGemoji]}>{children}</ReactMarkdown>
    </Typography>
  );
}
