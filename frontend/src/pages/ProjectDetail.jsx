import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, GitBranch, MoreHorizontal, Network, Pencil, Plus, Trash2, UserPlus, X } from 'lucide-react'
import { addProjectMember, deleteProject, getProject, getProjectMembers, removeProjectMember, renameProject, updateProjectMember } from '../api/projectApi'
import { deleteDiagram, renameDiagram } from '../api/diagramApi'
import { createVersion, deleteVersion, getVersionDiagrams, getVersions, renameVersion } from '../api/versionApi'
import { apiErrorMessage } from '../api/errorMessage'
import Toast from '../components/Toast'
import { useAgentRuntimeContext } from '../context/AgentRuntimeContext'
import { fullDate, initials, timeAgo } from '../utils/format'

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

  if (loading) return <main className="page"><div className="skeleton-list" aria-hidden="true"><span/><span/><span/></div></main>
  if (!project) return <main className="page"><p className="error-banner" role="alert">{error}</p></main>
  const editorUrl = (id) => `/projects/${projectId}/versions/${activeVersion.id}/diagrams/${id}`
  const newDiagramUrl = `/projects/${projectId}/versions/${activeVersion.id}/diagrams/new`
  return <main className="page app-shell">
    <nav className="breadcrumb" aria-label="Ruta"><Link to="/"><ArrowLeft size={15}/> Proyectos</Link><span aria-hidden="true">/</span><span>{project.name}</span></nav>
    <header className="page-header project-heading">
      <div><h1>{project.name}</h1><p>{project.description || 'Sin descripción'}</p></div>
      <div className="header-actions">
        <span className={`role-badge role-${project.role.toLowerCase()}`}>{ROLE_LABELS[project.role]}</span>
        {project.role === 'OWNER' && <details className="action-menu"><summary aria-label="Acciones del proyecto"><MoreHorizontal size={18}/></summary><div><button onClick={() => { setTarget({ type: 'project', id: project.id }); setRenameName(project.name); setModal('rename') }}><Pencil size={15}/> Renombrar</button><button className="danger-action" onClick={() => setModal('delete-project')}><Trash2 size={15}/> Eliminar proyecto</button></div></details>}
        {canEdit && <Link className="button-link primary-button" to={newDiagramUrl}><Plus size={17}/> Nuevo diagrama</Link>}
      </div>
    </header>
    <section className="version-toolbar" aria-label="Versión">
      <GitBranch size={16} aria-hidden="true"/>
      <label>Versión<select value={activeVersion?.id || ''} onChange={(e) => navigate(`/projects/${projectId}/versions/${e.target.value}`)}>{versions.map((v) => <option key={v.id} value={v.id}>{v.name}{v.mainVersion ? ' (principal)' : ''}</option>)}</select></label>
      <span className="version-note">{activeVersion.mainVersion ? 'Versión principal del proyecto' : 'Copia independiente de los diagramas'}</span>
      <div className="version-actions">
        {project.role === 'OWNER' && !activeVersion.mainVersion && <><button className="secondary-button" onClick={() => { setTarget({ type: 'version', id: activeVersion.id }); setRenameName(activeVersion.name); setModal('rename') }}><Pencil size={15}/> Renombrar</button><button className="secondary-button danger-text" onClick={() => setModal('delete-version')}><Trash2 size={15}/> Eliminar</button></>}
        {canEdit && <button className="secondary-button" onClick={() => { setForm({ name: '', description: '', sourceVersionId: activeVersion.id }); setModal('new-version') }}><Plus size={16}/> Nueva versión</button>}
      </div>
    </section>
    <div className="section-heading"><h2>Diagramas <span className="count">{diagrams.length}</span></h2></div>
    {!diagrams.length && <div className="empty-state"><h2>Esta versión no tiene diagramas</h2><p>Crea un diagrama de clases y comparte el enlace con tu equipo para editarlo juntos.</p>{canEdit && <Link className="button-link primary-button" to={newDiagramUrl}><Plus size={16}/> Nuevo diagrama</Link>}</div>}
    {!!diagrams.length && <section className="diagram-table" aria-label="Diagramas"><div className="diagram-table-head"><span>Nombre</span><span>Tipo</span><span>Última edición</span><span><span className="sr-only">Acciones</span></span></div>{diagrams.map((diagram) => <article className="diagram-row" key={diagram.id}><div className="diagram-name"><Network size={16} aria-hidden="true"/><Link to={editorUrl(diagram.id)}>{diagram.name}</Link></div><span>Diagrama de clases</span><time dateTime={diagram.updatedAt} title={fullDate(diagram.updatedAt)}>{timeAgo(diagram.updatedAt)}</time><div className="diagram-card-actions"><Link className="text-button" to={editorUrl(diagram.id)}>Abrir</Link>{canEdit && <details className="action-menu"><summary aria-label={`Acciones de ${diagram.name}`}><MoreHorizontal size={18}/></summary><div><button onClick={() => { setTarget({ type: 'diagram', id: diagram.id }); setRenameName(diagram.name); setModal('rename') }}><Pencil size={15}/> Renombrar</button><button className="danger-action" onClick={() => { setTarget({ type: 'diagram', id: diagram.id, name: diagram.name }); setModal('delete-diagram') }}><Trash2 size={15}/> Eliminar</button></div></details>}</div></article>)}</section>}
    {project.role === 'OWNER' && <section className="members-section" id="members" aria-labelledby="members-title">
      <div className="section-heading"><h2 id="members-title">Miembros <span className="count">{members.length}</span></h2></div>
      <p className="section-intro">Los editores pueden modificar diagramas y versiones. Los lectores solo pueden verlos.</p>
      <form className="member-invite" onSubmit={addMember}><label className="sr-only" htmlFor="member-email">Email del usuario</label><input id="member-email" type="email" placeholder="email@ejemplo.com" value={email} onChange={(e) => setEmail(e.target.value)} required/><label className="sr-only" htmlFor="member-role">Rol</label><select id="member-role" value={memberRole} onChange={(e) => setMemberRole(e.target.value)}><option value="EDITOR">Editor</option><option value="VIEWER">Lector</option></select><button className="primary-button"><UserPlus size={16}/> Agregar</button></form>
      {memberError && <p className="form-error" role="alert">{memberError}</p>}
      <div className="member-list">{members.map((m) => <article className="member-row" key={m.userId}><span className="avatar" aria-hidden="true">{initials(m.name)}</span><div className="member-identity"><strong>{m.name}</strong><span>{m.email}</span></div>{m.role === 'OWNER' ? <span className="role-badge role-owner">Propietario</span> : <select aria-label={`Rol de ${m.name}`} value={m.role} onChange={(e) => changeMember(m.userId, e.target.value)}><option value="EDITOR">Editor</option><option value="VIEWER">Lector</option></select>}{m.role !== 'OWNER' ? <button className="icon-button" title="Quitar del proyecto" aria-label={`Quitar a ${m.name}`} onClick={() => removeMember(m.userId)}><X size={16}/></button> : <span/>}</article>)}</div>
    </section>}
    {modal === 'new-version' && <Modal title="Nueva versión" close={() => setModal()}><p className="modal-intro">Copia los diagramas de la versión elegida. Los cambios en la copia no afectan a la original.</p><form className="form modal-form" onSubmit={(e) => { e.preventDefault(); perform(async () => { const created = await createVersion(projectId, form); navigate(`/projects/${projectId}/versions/${created.data.id}`) }, 'Versión creada.') }}><label>Nombre<input maxLength={120} autoFocus value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required/></label><label>Descripción <span className="optional">opcional</span><input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })}/></label><label>Copiar desde<select value={form.sourceVersionId} onChange={(e) => setForm({ ...form, sourceVersionId: e.target.value })}>{versions.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}</select></label><Actions close={() => setModal()} busy={busy} label="Crear versión"/></form></Modal>}
    {modal === 'rename' && <Modal title="Renombrar" close={() => setModal()}><form className="form modal-form" onSubmit={(e) => { e.preventDefault(); perform(() => target.type === 'project' ? renameProject(projectId, renameName) : target.type === 'version' ? renameVersion(projectId, target.id, renameName) : renameDiagram(target.id, renameName), 'Nombre actualizado.') }}><label>Nombre<input maxLength={120} autoFocus value={renameName} onChange={(e) => setRenameName(e.target.value)} required/></label><Actions close={() => setModal()} busy={busy} label="Guardar nombre"/></form></Modal>}
    {modal === 'delete-project' && <Modal title="Eliminar proyecto" close={() => setModal()}><p>Se eliminará <strong>{project.name}</strong> con todas sus versiones, diagramas y artefactos. No se puede deshacer.</p><label className="confirm-name">Escribe <strong>{project.name}</strong> para confirmar<input value={deleteName} onChange={(e) => setDeleteName(e.target.value)}/></label><div className="modal-actions"><button className="secondary-button" onClick={() => setModal()}>Cancelar</button><button className="danger-button" disabled={busy || deleteName !== project.name} onClick={async () => { setBusy(true); try { await deleteProject(projectId); navigate('/') } catch (e) { setError(apiErrorMessage(e, 'No se pudo eliminar el proyecto.')); setBusy(false) } }}>Eliminar proyecto</button></div></Modal>}
    {modal === 'delete-version' && <Modal title="Eliminar versión" close={() => setModal()}><p>Se eliminará <strong>{activeVersion.name}</strong> con todos sus diagramas. La versión principal no se modifica.</p><div className="modal-actions"><button className="secondary-button" onClick={() => setModal()}>Cancelar</button><button className="danger-button" disabled={busy} onClick={() => perform(async () => { await deleteVersion(projectId, activeVersion.id); const main = versions.find((v) => v.mainVersion); navigate(`/projects/${projectId}/versions/${main.id}`) }, 'Versión eliminada.')}>Eliminar versión</button></div></Modal>}
    {modal === 'delete-diagram' && <Modal title={`Eliminar ${target.name}`} close={() => setModal()}><p>Solo se elimina de la versión <strong>{activeVersion.name}</strong>. Las copias en otras versiones no cambian.</p><div className="modal-actions"><button className="secondary-button" onClick={() => setModal()}>Cancelar</button><button className="danger-button" disabled={busy} onClick={() => perform(() => deleteDiagram(target.id), 'Diagrama eliminado.')}>Eliminar diagrama</button></div></Modal>}
    {error && <p className="error-banner" role="alert">{error}</p>}<Toast message={notice} onClose={() => setNotice('')}/>
  </main>
}
function Modal({ title, close, children }) { return <div className="export-backdrop" onMouseDown={close}><section className="export-dialog compact-dialog" role="dialog" aria-modal="true" onMouseDown={(e) => e.stopPropagation()}><div className="modal-heading"><h2>{title}</h2><button className="icon-button" onClick={close} aria-label="Cerrar"><X size={18}/></button></div>{children}</section></div> }
function Actions({ close, busy, label }) { return <div className="modal-actions"><button type="button" className="secondary-button" onClick={close}>Cancelar</button><button className="primary-button" disabled={busy}>{label}</button></div> }
export default ProjectDetail
