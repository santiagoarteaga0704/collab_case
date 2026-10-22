import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, MoreHorizontal, Network, Pencil, Plus, Trash2, X } from 'lucide-react'
import { addProjectMember, deleteProject, getProject, getProjectMembers, removeProjectMember, renameProject, updateProjectMember } from '../api/projectApi'
import { deleteDiagram, renameDiagram } from '../api/diagramApi'
import { createVersion, deleteVersion, getVersionDiagrams, getVersions, renameVersion } from '../api/versionApi'
import { apiErrorMessage } from '../api/errorMessage'
import Toast from '../components/Toast'
import { useAgentRuntimeContext } from '../context/AgentRuntimeContext'

const ROLE_LABELS = { OWNER: 'Propietario', EDITOR: 'Editor', VIEWER: 'Lector' }

function ProjectDetail() {
  const { projectId, versionId } = useParams()
  const navigate = useNavigate()
  const { updateRuntimeContext } = useAgentRuntimeContext()
  const [project, setProject] = useState(), [versions, setVersions] = useState([]), [activeVersion, setActiveVersion] = useState()
  const [diagrams, setDiagrams] = useState([]), [members, setMembers] = useState([]), [loading, setLoading] = useState(true)
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [modal, setModal] = useState()
  const [form, setForm] = useState({ name: '', description: '', sourceVersionId: '' }), [target, setTarget] = useState()
  const [renameName, setRenameName] = useState(''), [deleteName, setDeleteName] = useState(''), [busy, setBusy] = useState(false)
  const [email, setEmail] = useState(''), [memberRole, setMemberRole] = useState('EDITOR'), [memberError, setMemberError] = useState('')

  async function load() {
    setError('')
    try {
      const [projectResponse, versionsResponse] = await Promise.all([getProject(projectId), getVersions(projectId)])
      const available = versionsResponse.data
      const selected = available.find((item) => item.id === versionId) || available.find((item) => item.mainVersion)
      setProject(projectResponse.data); setVersions(available); setActiveVersion(selected)
      if (selected) {
        setDiagrams((await getVersionDiagrams(projectId, selected.id)).data)
        if (versionId !== selected.id) navigate(`/projects/${projectId}/versions/${selected.id}`, { replace: true })
      }
      if (projectResponse.data.role === 'OWNER') setMembers((await getProjectMembers(projectId)).data)
      updateRuntimeContext({ projectId, projectName: projectResponse.data.name, versionId: selected?.id || null, versionName: selected?.name || null, diagramId: null, diagramName: null, role: projectResponse.data.role })
    } catch (requestError) { setError(apiErrorMessage(requestError, 'No se pudo cargar el proyecto.')) }
    finally { setLoading(false) }
  }
  useEffect(() => { void Promise.resolve().then(load) }, [projectId, versionId]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { const refresh = () => load(); window.addEventListener('agent:data-changed', refresh); return () => window.removeEventListener('agent:data-changed', refresh) })

  const canEdit = project && project.role !== 'VIEWER'
  async function perform(work, success) { setBusy(true); setError(''); try { await work(); setModal(); setTarget(); setDeleteName(''); setNotice(success); await load() } catch (e) { setError(apiErrorMessage(e, 'No se pudo completar la operación.')) } finally { setBusy(false) } }
  async function addMember(event) { event.preventDefault(); setMemberError(''); try { await addProjectMember(projectId, { email: email.trim(), role: memberRole }); setEmail(''); setMembers((await getProjectMembers(projectId)).data) } catch (e) { setMemberError(apiErrorMessage(e, 'No se pudo agregar el miembro.')) } }
  async function changeMember(userId, role) { try { await updateProjectMember(projectId, userId, { role }); setMembers((await getProjectMembers(projectId)).data) } catch (e) { setMemberError(apiErrorMessage(e, 'No se pudo cambiar el rol.')) } }
  async function removeMember(userId) { try { await removeProjectMember(projectId, userId); setMembers((await getProjectMembers(projectId)).data) } catch (e) { setMemberError(apiErrorMessage(e, 'No se pudo eliminar el miembro.')) } }

  if (loading) return <main className="page"><p>Cargando proyecto...</p></main>
  if (!project) return <main className="page"><p className="error">{error}</p></main>
  const editorUrl = (id) => `/projects/${projectId}/versions/${activeVersion.id}/diagrams/${id}`
  return <main className="page app-shell">
    <nav className="breadcrumb"><Link to="/"><ArrowLeft size={16}/> Proyectos</Link><span>/</span><span>{project.name}</span></nav>
    <header className="page-header app-topbar project-heading"><div><h1>{project.name}</h1><p>{project.description || 'Sin descripción'}</p><span className="role-badge">{ROLE_LABELS[project.role]}</span></div><div className="header-actions">{canEdit && <Link className="button-link" to={`/projects/${projectId}/versions/${activeVersion.id}/diagrams/new`}><Plus size={17}/> Nuevo diagrama</Link>}{project.role === 'OWNER' && <details className="action-menu"><summary aria-label="Acciones del proyecto"><MoreHorizontal/></summary><div><button onClick={() => { setTarget({ type: 'project', id: project.id }); setRenameName(project.name); setModal('rename') }}><Pencil size={15}/> Renombrar</button><button className="danger-action" onClick={() => setModal('delete-project')}><Trash2 size={15}/> Eliminar proyecto</button></div></details>}</div></header>
    <section className="version-toolbar"><label>Versión:<select value={activeVersion?.id || ''} onChange={(e) => navigate(`/projects/${projectId}/versions/${e.target.value}`)}>{versions.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}</select></label>{canEdit && <button onClick={() => { setForm({ name: '', description: '', sourceVersionId: activeVersion.id }); setModal('new-version') }}><Plus size={16}/> Nueva versión</button>}{project.role === 'OWNER' && !activeVersion.mainVersion && <><button className="secondary-button" onClick={() => { setTarget({ type: 'version', id: activeVersion.id }); setRenameName(activeVersion.name); setModal('rename') }}><Pencil size={15}/> Renombrar</button><button className="danger-button" onClick={() => setModal('delete-version')}><Trash2 size={15}/> Eliminar</button></>}</section>
    <nav className="repo-tabs"><a className="active">Diagramas <span>{diagrams.length}</span></a><a>Secuencias</a>{project.role === 'OWNER' && <a href="#members">Miembros</a>}<a>Configuración</a></nav>
    <div className="section-heading"><h2>Diagramas</h2></div>
    {!diagrams.length && <div className="empty-state"><Network size={36}/><h2>No hay diagramas en esta versión</h2><p>Puedes crear uno manualmente o pedírselo al Agente.</p>{canEdit && <Link className="button-link" to={`/projects/${projectId}/versions/${activeVersion.id}/diagrams/new`}><Plus size={16}/> Nuevo diagrama</Link>}</div>}
    {!!diagrams.length && <section className="diagram-table"><div className="diagram-table-head"><span>Nombre</span><span>Tipo</span><span>Actualizado</span><span>Acciones</span></div>{diagrams.map((diagram) => <article className="diagram-row" key={diagram.id}><div className="diagram-name"><Network size={17}/><Link to={editorUrl(diagram.id)}>{diagram.name}</Link></div><span>Diagrama de clases</span><small>{new Date(diagram.updatedAt).toLocaleString()}</small><div className="diagram-card-actions"><Link className="text-button" to={editorUrl(diagram.id)}>Abrir</Link>{canEdit && <details className="action-menu"><summary aria-label={`Acciones de ${diagram.name}`}><MoreHorizontal size={19}/></summary><div><button onClick={() => { setTarget({ type: 'diagram', id: diagram.id }); setRenameName(diagram.name); setModal('rename') }}><Pencil size={15}/> Renombrar</button><button className="danger-action" onClick={() => { setTarget({ type: 'diagram', id: diagram.id, name: diagram.name }); setModal('delete-diagram') }}><Trash2 size={15}/> Eliminar</button></div></details>}</div></article>)}</section>}
    {project.role === 'OWNER' && <section><h2>Miembros</h2><form className="form" onSubmit={addMember}><label>Email del usuario<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required/></label><label>Rol<select value={memberRole} onChange={(e) => setMemberRole(e.target.value)}><option value="EDITOR">Editor</option><option value="VIEWER">Lector</option></select></label><button>Agregar miembro</button></form>{memberError && <p className="error">{memberError}</p>}<div className="card-list">{members.map((m) => <article className="card member-row" key={m.userId}><strong>{m.name}</strong><span>{m.email}</span>{m.role === 'OWNER' ? <span className="role-badge">Propietario</span> : <select value={m.role} onChange={(e) => changeMember(m.userId, e.target.value)}><option value="EDITOR">Editor</option><option value="VIEWER">Lector</option></select>}{m.role !== 'OWNER' && <button className="secondary-button" onClick={() => removeMember(m.userId)}>Eliminar</button>}</article>)}</div></section>}
    {modal === 'new-version' && <Modal title="Nueva versión" close={() => setModal()}><form className="form modal-form" onSubmit={(e) => { e.preventDefault(); perform(async () => { const created = await createVersion(projectId, form); navigate(`/projects/${projectId}/versions/${created.data.id}`) }, 'Versión creada.') }}><label>Nombre<input maxLength={120} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required/></label><label>Descripción<input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })}/></label><label>Crear desde<select value={form.sourceVersionId} onChange={(e) => setForm({ ...form, sourceVersionId: e.target.value })}>{versions.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}</select></label><Actions close={() => setModal()} busy={busy} label="Crear versión"/></form></Modal>}
    {modal === 'rename' && <Modal title="Renombrar" close={() => setModal()}><form className="form modal-form" onSubmit={(e) => { e.preventDefault(); perform(() => target.type === 'project' ? renameProject(projectId, renameName) : target.type === 'version' ? renameVersion(projectId, target.id, renameName) : renameDiagram(target.id, renameName), 'Nombre actualizado.') }}><label>Nombre<input maxLength={120} value={renameName} onChange={(e) => setRenameName(e.target.value)} required/></label><Actions close={() => setModal()} busy={busy} label="Guardar"/></form></Modal>}
    {modal === 'delete-project' && <Modal title="Eliminar proyecto" close={() => setModal()}><p>Se eliminará el proyecto <strong>{project.name}</strong> junto con todas sus versiones, diagramas y artefactos asociados.</p><label className="confirm-name">Escribe <strong>{project.name}</strong> para confirmar<input value={deleteName} onChange={(e) => setDeleteName(e.target.value)}/></label><div className="modal-actions"><button className="secondary-button" onClick={() => setModal()}>Cancelar</button><button className="danger-button" disabled={busy || deleteName !== project.name} onClick={async () => { setBusy(true); try { await deleteProject(projectId); navigate('/') } catch (e) { setError(apiErrorMessage(e, 'No se pudo eliminar el proyecto.')); setBusy(false) } }}>Eliminar permanentemente</button></div></Modal>}
    {modal === 'delete-version' && <Modal title="Eliminar versión" close={() => setModal()}><p>Se eliminará <strong>{activeVersion.name}</strong> y todos sus diagramas. La versión main no se verá afectada.</p><div className="modal-actions"><button className="secondary-button" onClick={() => setModal()}>Cancelar</button><button className="danger-button" disabled={busy} onClick={() => perform(async () => { await deleteVersion(projectId, activeVersion.id); const main = versions.find((v) => v.mainVersion); navigate(`/projects/${projectId}/versions/${main.id}`) }, 'Versión eliminada.')}>Eliminar versión</button></div></Modal>}
    {modal === 'delete-diagram' && <Modal title={`¿Eliminar ${target.name}?`} close={() => setModal()}><p>Solo se eliminará este diagrama de la versión <strong>{activeVersion.name}</strong>. Las copias de otras versiones no se modificarán.</p><div className="modal-actions"><button className="secondary-button" onClick={() => setModal()}>Cancelar</button><button className="danger-button" disabled={busy} onClick={() => perform(() => deleteDiagram(target.id), 'Diagrama eliminado.')}>Eliminar diagrama</button></div></Modal>}
    {error && <p className="error">{error}</p>}<Toast message={notice} onClose={() => setNotice('')}/>
  </main>
}
function Modal({ title, close, children }) { return <div className="export-backdrop" onMouseDown={close}><section className="export-dialog compact-dialog" role="dialog" aria-modal="true" onMouseDown={(e) => e.stopPropagation()}><div className="modal-heading"><h2>{title}</h2><button className="icon-button" onClick={close} aria-label="Cerrar"><X size={18}/></button></div>{children}</section></div> }
function Actions({ close, busy, label }) { return <div className="modal-actions"><button type="button" className="secondary-button" onClick={close}>Cancelar</button><button disabled={busy}>{label}</button></div> }
export default ProjectDetail
