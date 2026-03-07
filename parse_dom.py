import sys
import re

def parse_dom(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        html = f.read()

    # Find "Spreadsheet" in the html
    matches = [m.start() for m in re.finditer(r'Spreadsheet', html)]
    print(f"Found {len(matches)} occurrences of 'Spreadsheet'")
    
    for idx, pos in enumerate(matches):
        print(f"\n--- Match {idx + 1} ---")
        start = max(0, pos - 200)
        end = min(len(html), pos + 200)
        snippet = html[start:end]
        print(snippet)

if __name__ == "__main__":
    parse_dom(sys.argv[1])
