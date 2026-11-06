import React, {useState} from "react";

function CopyButton({ value, label = "Copy URL" }) {
    const [copied, setCopied] = useState(false);

    const handleCopy = async () => {
        if (!value) return;
        try {
            await navigator.clipboard.writeText(value);
            setCopied(true);
            setTimeout(() => setCopied(false), 1200);
        } catch (err) {
            console.error(err);
        }
    };

    if (!value) return null;

    return (
        <button
            type="button"
            onClick={handleCopy}
            className="ml-2 p-1 rounded hover:bg-gray-200 focus:outline-none"
            title={copied ? "Copied" : label}
            aria-label={copied ? "Copied" : label}
        >
            {copied ? (
                <svg className="h-4 w-4 text-green-500" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                </svg>
            ) : (
                <svg className="h-4 w-4 text-gray-500" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <rect x="9" y="9" width="13" height="13" rx="2" />
                    <path d="M5 15V5a2 2 0 012-2h10" />
                </svg>
            )}
        </button>
    );
}

export default CopyButton;
