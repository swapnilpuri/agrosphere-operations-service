"""Compare meaningful local OpenAPI constraints; required names form an unordered set.

Do not discard $ref sibling constraints or relax types, formats, ranges, property
closure, parameters or responses. Metadata does not affect wire behavior.
"""
METADATA={'description','title','example','examples'}


def resolve(node,document):
    if isinstance(node,list):return [resolve(value,document) for value in node]
    if not isinstance(node,dict):return node
    if '$ref' in node:
        reference=node['$ref']
        assert reference.startswith('#/'),'Only pinned local OpenAPI references are permitted'
        target=document
        for key in reference[2:].split('/'):target=target[key.replace('~1','/').replace('~0','~')]
        resolved=resolve(target,document)
        siblings={k:v for k,v in node.items() if k!='$ref' and k not in METADATA}
        return {'allOf':[resolved,resolve(siblings,document)]} if siblings else resolved
    result={k:resolve(value,document) for k,value in node.items() if k not in METADATA}
    if isinstance(result.get('required'),list):result['required']=sorted(result['required'])
    return result
