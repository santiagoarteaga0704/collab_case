import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Clock3, FolderKanban, MoreHorizontal, Pencil, Plus, Trash2, X } from 'lucide-react'
import { createProject, deleteProject, getProjects, renameProject } from '../api/projectApi'
import { apiErrorMessage } from '../api/errorMessage'
import Toast from '../components/Toast'

const ROLE_LABELS = { OWNER: 'Propietario', EDITOR: 'Editor', VIEWER: 'Lector' }

export default function ProjectList() {
  const [projects, setProjects] = useState([]), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const [modal, setModal] = useState(), [target, setTarget] = useState(), [name, setName] = useState(''), [description, setDescription] = useState(''), [confirmName, setConfirmName] = useState(''), [notice, setNotice] = useState('')
  async function load() { try { setProjects((await getProjects()).data) } catch (e) { setError(apiErrorMessage(e, 'No se pudieron cargar los proyectos.')) } finally { setLoading(false) } }
  useEffect(() => { void Promise.resolve().then(load); const refresh = () => load(); window.addEventListener('agent:data-changed', refresh); return () => window.removeEventListener('agent:data-changed', refresh) }, [])
  async function submit(event) { event.preventDefault(); setError(''); try { if (modal === 'new') { await createProject({ name: name.trim(), description }); setNotice('Proyecto creado correctamente.') } else { await renameProject(target.id, name.trim()); setNotice('Proyecto renombrado correctamente.') } setModal(); await load() } catch (e) { setError(apiErrorMessage(e, 'No se pudo guardar el proyecto.')) } }
  async function remove() { try { await deleteProject(target.id); setModal(); setConfirmName(''); setNotice('Proyecto eliminado.'); await load() } catch (e) { setError(apiErrorMessage(e, 'No se pudo eliminar el proyecto.')) } }
  return <div className="dashboard-layout"><aside className="dashboard-sidebar"><strong>Espacio de trabajo</strong><nav><a className="active"><FolderKanban size={17}/> Proyectos</a><a><Clock3 size={17}/> Recientes</a></nav></aside><main className="content-page">
    <header className="content-header"><div><h1>Proyectos</h1><p>Modela, colabora y genera software desde UML.</p></div><button onClick={() => { setName(''); setDescription(''); setModal('new') }}><Plus size={17}/> Nuevo proyecto</button></header>
    {error && <p className="error-banner">{error}</p>}{loading && <p>Cargando proyectos...</p>}
    {!loading && !projects.length && <div className="empty-state"><FolderKanban size={36}/><h2>No hay proyectos todavía</h2><p>Crea tu primer espacio de modelado o pídeselo al Agente.</p><button onClick={() => setModal('new')}><Plus size={16}/> Nuevo proyecto</button></div>}
    <section className="repository-list">{projects.map((project) => <article className="repository-row" key={project.id}><span className="repo-icon"><FolderKanban size={19}/></span><div className="repo-main"><Link to={`/projects/${project.id}`}>{project.name}</Link><p>{project.description || 'Sin descripción'}</p><small>Actualizado {new Date(project.updatedAt).toLocaleString()}</small></div><span className="role-badge">{ROLE_LABELS[project.role] || project.role}</span>{project.role === 'OWNER' && <details className="action-menu"><summary aria-label={`Acciones de ${project.name}`}><MoreHorizontal size={19}/></summary><div><button onClick={() => { setTarget(project); setName(project.name); setModal('rename') }}><Pencil size={15}/> Renombrar</button><button className="danger-action" onClick={() => { setTarget(project); setModal('delete') }}><Trash2 size={15}/> Eliminar</button></div></details>}</article>)}</section>
    {(modal === 'new' || modal === 'rename') && <Modal title={modal === 'new' ? 'Nuevo proyecto' : 'Renombrar proyecto'} close={() => setModal()}><form className="form modal-form" onSubmit={submit}><label>Nombre<input value={name} maxLength={120} onChange={(e) => setName(e.target.value)} required/></label>{modal === 'new' && <label>Descripción<textarea value={description} onChange={(e) => setDescription(e.target.value)}/></label>}<div className="modal-actions"><button type="button" className="secondary-button" onClick={() => setModal()}>Cancelar</button><button>Guardar</button></div></form></Modal>}
    {modal === 'delete' && <Modal title="Eliminar proyecto" close={() => setModal()}><p>Se eliminará <strong>{target.name}</strong> y todos sus artefactos.</p><label className="confirm-name">Escribe <strong>{target.name}</strong><input value={confirmName} onChange={(e) => setConfirmName(e.target.value)}/></label><div className="modal-actions"><button className="secondary-button" onClick={() => setModal()}>Cancelar</button><button className="danger-button" disabled={confirmName !== target.name} onClick={remove}>Eliminar permanentemente</button></div></Modal>}
    <Toast message={notice} onClose={() => setNotice('')}/>
  </main></div>
}
function Modal({ title, close, children }) { return <div className="export-backdrop" onMouseDown={close}><section className="export-dialog compact-dialog" onMouseDown={(e) => e.stopPropagation()}><div className="modal-heading"><h2>{title}</h2><button className="icon-button" onClick={close}><X size={18}/></button></div>{children}</section></div> }
