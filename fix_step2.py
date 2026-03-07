path = r'c:\Users\Janmejay\.gemini\antigravity\scratch\automate-workflow-test\src\test\java\testing\CreateConnectWorkflowTest.java'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

content_norm = content.replace('\r\n', '\n')

old_key = 'driver.get("https://alphaconnectcloud.appypie.com/customeditor/");'
if old_key in content_norm:
    print('FOUND key line')
else:
    print('NOT FOUND - listing relevant lines:')
    lines = content_norm.split('\n')
    for i, line in enumerate(lines[53:65], 54):
        print(repr(str(i) + ': ' + line))
