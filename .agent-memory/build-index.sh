#!/bin/bash
OUTPUT=".agent-memory/project-structure.json"
echo "{\"last_updated\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\", \"files\": [" > "$OUTPUT"
find . -type f -not -path "./.git/*" -not -path "./.agent-memory/*" -not -path "./node_modules/*" | while read -r file; do
    echo "  {\"path\": \"$file\"}," >> "$OUTPUT"
done
sed -i '$ s/,$//' "$OUTPUT"
echo " ]}" >> "$OUTPUT"
echo "Indexed $(grep -c '"path"' "$OUTPUT") files -> $OUTPUT"
