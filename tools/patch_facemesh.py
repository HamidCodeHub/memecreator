# Rewrites the MediaPipe face landmark ONNX export so OpenCV 4.9 can load it (see src/main/resources/models/README.md).
# Usage: python tools/patch_facemesh.py face_landmark_barracuda.onnx face_landmark_468.onnx   (needs: pip install onnx numpy)
import sys, onnx, numpy as np
from onnx import numpy_helper, helper
src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src); g = m.graph
inits = {i.name: i for i in g.initializer}
def arr(n): return numpy_helper.to_array(inits[n])
new_nodes, new_inits = [], []
expand_map = {}
for n in g.node:
    if n.op_type == 'Expand':
        a = arr(n.input[0])                       # (1,C,1,1)
        name = n.output[0]
        new_inits.append(numpy_helper.from_array(a.reshape(a.shape[1], 1, 1).astype(np.float32), name))
        continue
    if n.op_type == 'ConstantOfShape':
        shape = arr(n.input[0]).astype(np.int64)
        new_inits.append(numpy_helper.from_array(np.zeros(shape, np.float32), n.output[0]))
        continue
    new_nodes.append(n)
for i, n in enumerate(new_nodes):
    if not n.name: n.name = f'{n.op_type.lower()}_{i}'
del g.node[:]; g.node.extend(new_nodes)
used = {x for n in new_nodes for x in n.input}
keep = [i for i in g.initializer if i.name in used]
del g.initializer[:]; g.initializer.extend(keep + new_inits)
# drop graph inputs that duplicate initializers (old onnx style)
names = {i.name for i in g.initializer}
ins = [i for i in g.input if i.name not in names]
del g.input[:]; g.input.extend(ins)
onnx.checker.check_model(m)
onnx.save(m, dst)
print('ok', len(new_nodes), 'nodes')
